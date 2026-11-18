package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ClipboardStoreTest {

    @Test fun expiresUnpinnedAfterOneHourAndKeepsPinned() {
        var now = 1_000_000L
        val file = Files.createTempFile("clip_pin", ".txt").toFile()
        val store = ClipboardStore(file) { now }

        store.onCopied("temp code 123456")
        store.onCopied("my@email.com")
        store.togglePin("my@email.com")

        assertEquals("my@email.com", store.freshClip())
        assertEquals(listOf("my@email.com", "temp code 123456"), store.items().map { it.text })

        // After 61 seconds: fresh paste chip disappears, items remain
        now += 61_000L
        assertNull(store.freshClip())
        assertEquals(2, store.count())

        // After 61 minutes: unpinned RAM item vanishes automatically; pinned stays
        now += 60 * 60 * 1000L
        assertEquals(listOf("my@email.com"), store.items().map { it.text })

        // New instance (simulating process restart): only pinned item is loaded from disk
        val reloaded = ClipboardStore(file) { now }
        assertTrue(reloaded.hasAny())
        assertEquals(listOf("my@email.com"), reloaded.items().map { it.text })

        reloaded.clear(includePinned = true)
        assertFalse(reloaded.hasAny())
    }
}
