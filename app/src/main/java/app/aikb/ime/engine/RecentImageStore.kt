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
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ime-img").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val thumbCache = object : LruCache<Long, Bitmap>(28) {}

    @Volatile var latestFresh: ImageItem? = null
        private set
    @Volatile var latestFreshThumb: Bitmap? = null
        private set
    private var dismissedId: Long = -1L
    @Volatile var internalUriCopyAt: Long = 0L
        private set

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
        latestFresh?.let { dismissedId = it.id }
        latestFresh = null
        latestFreshThumb = null
        onFreshChanged()
    }

    /** Checks if `latestFresh` is still within the 90-second freshness window. */
    fun activeFresh(): Pair<ImageItem, Bitmap?>? {
        val item = latestFresh ?: return null
        val nowSec = System.currentTimeMillis() / 1000L
        if (item.id == dismissedId || nowSec - item.dateAddedSec > FRESH_WINDOW_SEC) {
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
            val recent = queryRecentBlocking(limit = 3, screenshotsOnly = false)
            val nowSec = System.currentTimeMillis() / 1000L
            val fresh = recent.firstOrNull { it.id != dismissedId && nowSec - it.dateAddedSec in -5L..FRESH_WINDOW_SEC }
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
            // Warm thumbnails for the first visible cells
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
     * Sends [item] directly into the current editor via `InputConnectionCompat.commitContent`
     * (supported natively by WeChat, QQ, Telegram, Messages, Chrome, Notes, etc.), falling back
     * to copying the `content://` image URI to the clipboard when unsupported.
     */
    fun sendImage(ic: InputConnection?, editorInfo: EditorInfo?, item: ImageItem): Boolean {
        if (ic == null || editorInfo == null) return false
        val supportedMimes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        val mimeSupported = supportedMimes.any { pattern ->
            ClipDescription.compareMimeTypes(item.mimeType, pattern) || ClipDescription.compareMimeTypes("image/*", pattern)
        }
        val desc = ClipDescription(item.displayName, arrayOf(item.mimeType))
        val contentInfo = InputContentInfoCompat(item.uri, desc, null)
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION

        if (mimeSupported || supportedMimes.isNotEmpty()) {
            val ok = runCatching {
                contentInfo.requestPermission()
                InputConnectionCompat.commitContent(ic, editorInfo, contentInfo, flags, null)
            }.getOrDefault(false)
            if (ok) {
                if (latestFresh?.id == item.id) dismissFresh()
                return true
            }
        }

        // Try direct commitContent even when app forgot to populate contentMimeTypes
        val fallbackDirect = runCatching {
            InputConnectionCompat.commitContent(ic, editorInfo, contentInfo, flags, null)
        }.getOrDefault(false)
        if (fallbackDirect) {
            if (latestFresh?.id == item.id) dismissFresh()
            return true
        }

        // Universal fallback: copy image URI to system clipboard so user can paste in chat apps
        runCatching {
            internalUriCopyAt = System.currentTimeMillis()
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newUri(context.contentResolver, item.displayName, item.uri))
            Toast.makeText(context, "当前输入框不支持直传，已复制图片可长按粘贴", Toast.LENGTH_SHORT).show()
        }
        if (latestFresh?.id == item.id) dismissFresh()
        return false
    }

    companion object {
        private const val FRESH_WINDOW_SEC = 90L

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
