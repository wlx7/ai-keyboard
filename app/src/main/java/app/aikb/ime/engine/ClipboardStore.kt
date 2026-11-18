package app.aikb.ime.engine

import java.io.File

/**
 * Privacy-first local clipboard manager modelled after Gboard:
 *   - Sensitive clips (Android 13+ password managers) are ignored by the caller.
 *   - Unpinned clips live ONLY in RAM (never written to disk) and auto-expire after 1 hour.
 *   - Pinned clips are saved in app-private storage (no network permission exists).
 *   - Newly copied text surfaces as a one-tap paste chip for 60 seconds.
 */
class ClipboardStore(
    private val pinnedFile: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Item(
        val text: String,
        val timestamp: Long,
        val pinned: Boolean,
    )

    private val pinnedItems = ArrayList<Item>()
    private val recentItems = ArrayList<Item>()
    private var freshText: String? = null
    private var freshAt: Long = 0L

    init {
        loadPinned()
    }

    @Synchronized
    fun onCopied(raw: String) {
        val text = raw.trim { it == '\u0000' }
        if (text.isBlank() || text.length > MAX_TEXT_LEN) return
        val now = clock()
        purgeExpiredLocked(now)

        // If already pinned, just surface as fresh paste chip
        if (pinnedItems.any { it.text == text }) {
            freshText = text
            freshAt = now
            return
        }

        recentItems.removeAll { it.text == text }
        recentItems.add(0, Item(text, now, pinned = false))
        while (recentItems.size > MAX_RECENT) recentItems.removeAt(recentItems.lastIndex)
        freshText = text
        freshAt = now
    }

    /** Returns a newly copied clip (< 60s old) for the top-bar quick-paste chip, or null. */
    @Synchronized
    fun freshClip(): String? {
        val now = clock()
        purgeExpiredLocked(now)
        val t = freshText ?: return null
        if (now - freshAt > FRESH_WINDOW_MS) {
            freshText = null
            return null
        }
        return t
    }

    @Synchronized
    fun dismissFresh() {
        freshText = null
    }

    /** Pinned items first, then non-expired recent items (newest first). */
    @Synchronized
    fun items(): List<Item> {
        purgeExpiredLocked(clock())
        return pinnedItems + recentItems
    }

    @Synchronized
    fun hasAny(): Boolean {
        purgeExpiredLocked(clock())
        return pinnedItems.isNotEmpty() || recentItems.isNotEmpty()
    }

    @Synchronized
    fun count(): Int {
        purgeExpiredLocked(clock())
        return pinnedItems.size + recentItems.size
    }

    @Synchronized
    fun togglePin(text: String) {
        val now = clock()
        purgeExpiredLocked(now)
        val pIdx = pinnedItems.indexOfFirst { it.text == text }
        if (pIdx >= 0) {
            val removed = pinnedItems.removeAt(pIdx)
            recentItems.add(0, removed.copy(timestamp = now, pinned = false))
            while (recentItems.size > MAX_RECENT) recentItems.removeAt(recentItems.lastIndex)
            savePinnedLocked()
            return
        }
        val rIdx = recentItems.indexOfFirst { it.text == text }
        if (rIdx >= 0) {
            val item = recentItems.removeAt(rIdx)
            pinnedItems.add(0, item.copy(pinned = true))
            while (pinnedItems.size > MAX_PINNED) pinnedItems.removeAt(pinnedItems.lastIndex)
            savePinnedLocked()
        }
    }

    @Synchronized
    fun delete(text: String) {
        val changedPin = pinnedItems.removeAll { it.text == text }
        recentItems.removeAll { it.text == text }
        if (freshText == text) freshText = null
        if (changedPin) savePinnedLocked()
    }

    /** Clears all unpinned items immediately; if [includePinned] is true, wipes pinned items too. */
    @Synchronized
    fun clear(includePinned: Boolean = false) {
        recentItems.clear()
        freshText = null
        if (includePinned) {
            pinnedItems.clear()
            savePinnedLocked()
        }
    }

    @Synchronized
    fun remainingMinutes(item: Item): Int {
        if (item.pinned) return Int.MAX_VALUE
        val remMs = (TTL_MS - (clock() - item.timestamp)).coerceAtLeast(0L)
        return ((remMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    private fun purgeExpiredLocked(now: Long) {
        recentItems.removeAll { now - it.timestamp >= TTL_MS }
        if (freshText != null && now - freshAt > FRESH_WINDOW_MS) freshText = null
    }

    private fun loadPinned() {
        runCatching {
            if (!pinnedFile.exists()) return
            val now = clock()
            pinnedFile.readLines(Charsets.UTF_8).forEach { line ->
                if (line.isNotEmpty()) {
                    val decoded = unescape(line)
                    if (decoded.isNotBlank()) pinnedItems += Item(decoded, now, pinned = true)
                }
            }
        }
    }

    private fun savePinnedLocked() {
        runCatching {
            pinnedFile.parentFile?.mkdirs()
            val tmp = File(pinnedFile.path + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for (it in pinnedItems) {
                    w.write(escape(it.text))
                    w.write("\n")
                }
            }
            java.nio.file.Files.move(
                tmp.toPath(),
                pinnedFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    companion object {
        const val TTL_MS = 60 * 60 * 1000L       // 1 hour auto-clear (Gboard style)
        const val FRESH_WINDOW_MS = 60 * 1000L   // 60s quick-paste chip
        private const val MAX_RECENT = 20
        private const val MAX_PINNED = 24
        private const val MAX_TEXT_LEN = 4_000
    }
}
