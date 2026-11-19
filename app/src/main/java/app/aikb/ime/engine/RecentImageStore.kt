package app.aikb.ime.engine

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Offline MediaStore tracker & `InputConnectionCompat.commitContent` sender for Gboard-style
 * fresh screenshot chips & recent photo/screenshot sending — zero network permissions.
 */
class RecentImageStore(private val context: Context, private val onFreshChanged: () -> Unit) {

    data class ImageItem(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
        val dateAddedSec: Long,
        val isScreenshot: Boolean,
    )

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ime-img").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val thumbCache = object : LruCache<Long, Bitmap>(28) {}

    private val consumedIds = LinkedHashSet<Long>()
    private val consumedNames = LinkedHashSet<String>()
    @Volatile private var lastSentAtMs: Long = 0L

    @Volatile var latestFresh: ImageItem? = null
        private set
    @Volatile var latestFreshThumb: Bitmap? = null
        private set
    @Volatile var internalUriCopyAt: Long = 0L
        private set

    init {
        loadConsumedState()
    }

    @Synchronized
    private fun loadConsumedState() {
        prefs.getString(PREF_CONSUMED_IDS, "")
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.forEach { consumedIds.add(it) }
        prefs.getString(PREF_CONSUMED_NAMES, "")
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { consumedNames.add(it) }
    }

    @Synchronized
    private fun recordConsumedLocked(id: Long, displayName: String) {
        consumedIds.add(id)
        while (consumedIds.size > 50) {
            val first = consumedIds.iterator().next()
            consumedIds.remove(first)
        }
        if (displayName.isNotBlank()) {
            consumedNames.add(displayName)
            while (consumedNames.size > 50) {
                val first = consumedNames.iterator().next()
                consumedNames.remove(first)
            }
        }
        prefs.edit()
            .putString(PREF_CONSUMED_IDS, consumedIds.joinToString(","))
            .putString(PREF_CONSUMED_NAMES, consumedNames.joinToString("\n"))
            .apply()
    }

    @Synchronized
    private fun isConsumed(item: ImageItem): Boolean =
        item.id in consumedIds || (item.displayName.isNotBlank() && item.displayName in consumedNames)

    private fun markConsumed(item: ImageItem, isSend: Boolean) {
        synchronized(this) {
            recordConsumedLocked(item.id, item.displayName)
            if (isSend) lastSentAtMs = System.currentTimeMillis()
        }
        latestFresh = null
        latestFreshThumb = null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onFreshChanged()
        } else {
            mainHandler.post(onFreshChanged)
        }
    }

    private val observer = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            refreshFreshAsync()
        }
    }
    private var registered = false

    fun startObserving() {
        if (registered) return
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer,
            )
            registered = true
        }
    }

    fun stopObserving() {
        if (registered) {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
            registered = false
        }
        worker.shutdownNow()
    }

    fun hasPermission(): Boolean = hasImagePermission(context)

    fun dismissFresh() {
        val cur = latestFresh
        if (cur != null) {
            markConsumed(cur, isSend = false)
        } else {
            latestFreshThumb = null
            onFreshChanged()
        }
    }

    /** Checks if `latestFresh` is still within the 90-second freshness window and not already sent/dismissed. */
    fun activeFresh(): Pair<ImageItem, Bitmap?>? {
        val item = latestFresh ?: return null
        val nowSec = System.currentTimeMillis() / 1000L
        if (isConsumed(item) || nowSec - item.dateAddedSec > FRESH_WINDOW_SEC) {
            latestFresh = null
            latestFreshThumb = null
            return null
        }
        return item to latestFreshThumb
    }

    fun refreshFreshAsync() {
        if (!hasPermission()) {
            if (latestFresh != null) {
                latestFresh = null; latestFreshThumb = null
                mainHandler.post(onFreshChanged)
            }
            return
        }
        worker.execute {
            val recent = queryRecentBlocking(limit = 4, screenshotsOnly = false)
            val nowMs = System.currentTimeMillis()
            val nowSec = nowMs / 1000L
            val inPostSendCooldown = nowMs - lastSentAtMs in 0L..POST_SEND_COOLDOWN_MS

            var fresh: ImageItem? = null
            for (cand in recent) {
                if (nowSec - cand.dateAddedSec !in -5L..FRESH_WINDOW_SEC) continue
                if (isConsumed(cand)) continue
                // Chat apps (e.g. WeChat) often save a compressed copy to Pictures/WeiXin right after
                // sending an image; auto-consume non-screenshot echoes during the post-send window.
                if (inPostSendCooldown && !cand.isScreenshot) {
                    synchronized(this) { recordConsumedLocked(cand.id, cand.displayName) }
                    continue
                }
                fresh = cand
                break
            }

            val thumb = fresh?.let { loadThumbnailBlocking(it, 96) }
            val changed = fresh?.id != latestFresh?.id
            latestFresh = fresh
            latestFreshThumb = thumb
            if (changed || fresh != null) mainHandler.post(onFreshChanged)
        }
    }

    fun loadRecentAsync(screenshotsOnly: Boolean, limit: Int = 24, callback: (List<ImageItem>) -> Unit) {
        if (!hasPermission()) {
            mainHandler.post { callback(emptyList()) }
            return
        }
        worker.execute {
            val list = queryRecentBlocking(limit = limit, screenshotsOnly = screenshotsOnly)
            for (item in list.take(12)) loadThumbnailBlocking(item, 180)
            mainHandler.post { callback(list) }
        }
    }

    fun cachedThumb(id: Long): Bitmap? = synchronized(thumbCache) { thumbCache.get(id) }

    fun requestThumb(item: ImageItem, px: Int, onLoaded: () -> Unit) {
        if (cachedThumb(item.id) != null) return
        worker.execute {
            if (loadThumbnailBlocking(item, px) != null) {
                mainHandler.post(onLoaded)
            }
        }
    }

    private fun loadThumbnailBlocking(item: ImageItem, px: Int): Bitmap? {
        synchronized(thumbCache) { thumbCache.get(item.id)?.let { return it } }
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            }
        }.getOrNull()
        if (bmp != null) {
            synchronized(thumbCache) { thumbCache.put(item.id, bmp) }
        }
        return bmp
    }

    private fun queryRecentBlocking(limit: Int, screenshotsOnly: Boolean): List<ImageItem> = runCatching {
        val out = ArrayList<ImageItem>(limit)
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            }
        }.toTypedArray()

        val queryLimit = if (screenshotsOnly) limit * 4 else limit
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, queryLimit)
        }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            args,
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
            val bucketCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) else -1

            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(idCol)
                val name = (if (nameCol >= 0) c.getString(nameCol) else null).orEmpty()
                val mime = (if (mimeCol >= 0) c.getString(mimeCol) else null)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val added = if (dateCol >= 0) c.getLong(dateCol) else 0L
                val relPath = (if (pathCol >= 0) c.getString(pathCol) else null).orEmpty()
                val bucket = (if (bucketCol >= 0) c.getString(bucketCol) else null).orEmpty()
                val combined = "$name $relPath $bucket".lowercase()
                val isShot = "screenshot" in combined || "截屏" in combined || "截图" in combined || "screen_shot" in combined
                if (screenshotsOnly && !isShot) continue
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                out += ImageItem(id, uri, name.ifEmpty { "image_$id" }, mime, added, isShot)
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * Stages [item] into our own `FileProvider` cache (`content://app.aikb.ime.fileprovider/...`)
     * so `INPUT_CONTENT_GRANT_READ_URI_PERMISSION` genuinely delegates read access to browsers
     * (Chrome / WebView) and chat apps that do NOT hold `READ_MEDIA_IMAGES` themselves.
     */
    private fun stageForFileProvider(item: ImageItem, supportedMimes: Array<String>): Pair<Uri, String>? = runCatching {
        val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }

        val webFriendly = item.mimeType.lowercase() in setOf("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp")
        val directMatch = webFriendly && supportedMimes.any { pattern ->
            ClipDescription.compareMimeTypes(item.mimeType, pattern) || pattern == "*/*" || pattern == "image/*"
        }

        val dstFile: File
        val outMime: String
        if (directMatch) {
            val ext = when (item.mimeType.lowercase()) {
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            outMime = if (item.mimeType.lowercase() == "image/jpg") "image/jpeg" else item.mimeType
            dstFile = File(dir, "share_${item.id}.$ext")
            if (!dstFile.exists() || dstFile.length() == 0L) {
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    dstFile.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                } ?: return null
            }
        } else {
            // Transcode HEIC / exotic formats to universal JPEG so Chrome & web editors accept it cleanly
            outMime = "image/jpeg"
            dstFile = File(dir, "share_${item.id}.jpg")
            if (!dstFile.exists() || dstFile.length() == 0L) {
                val bmp = context.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
                dstFile.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            }
        }
        val fpUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dstFile)
        fpUri to outMime
    }.getOrNull()

    /**
     * Sends [item] directly into the current editor via `InputConnectionCompat.commitContent`
     * backed by our `FileProvider` URI, and immediately marks [item] as consumed.
     */
    fun sendImage(ic: InputConnection?, editorInfo: EditorInfo?, item: ImageItem): Boolean {
        latestFresh?.let { if (it.id != item.id) markConsumed(it, isSend = true) }
        markConsumed(item, isSend = true)

        if (ic == null || editorInfo == null || !editorAcceptsImages(editorInfo)) return false
        val supportedMimes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        val staged = stageForFileProvider(item, supportedMimes)
        val targetUri = staged?.first ?: item.uri
        val targetMime = staged?.second ?: item.mimeType

        val desc = ClipDescription(item.displayName, arrayOf(targetMime))
        val contentInfo = InputContentInfoCompat(targetUri, desc, null)
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION

        return runCatching {
            InputConnectionCompat.commitContent(ic, editorInfo, contentInfo, flags, null)
        }.getOrDefault(false)
    }

    companion object {
        private const val FRESH_WINDOW_SEC = 90L
        private const val POST_SEND_COOLDOWN_MS = 15_000L
        private const val PREF_CONSUMED_IDS = "consumed_image_ids"
        private const val PREF_CONSUMED_NAMES = "consumed_image_names"

        /**
         * Returns true ONLY when the target [editorInfo] explicitly advertises rich image support
         * via `EditorInfoCompat.getContentMimeTypes`. Plain `<textarea>`, URL bars, and text-only
         * fields return false so we never display an unusable image bubble.
         */
        fun editorAcceptsImages(editorInfo: EditorInfo?): Boolean {
            if (editorInfo == null) return false
            val mimes = EditorInfoCompat.getContentMimeTypes(editorInfo)
            if (mimes.isEmpty()) return false
            return mimes.any { pattern ->
                pattern == "*/*" ||
                    pattern.startsWith("image/") ||
                    ClipDescription.compareMimeTypes("image/png", pattern) ||
                    ClipDescription.compareMimeTypes("image/jpeg", pattern) ||
                    ClipDescription.compareMimeTypes("image/webp", pattern)
            }
        }

        fun hasImagePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }

        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
