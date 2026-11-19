package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import app.aikb.ime.engine.RecentImageStore
import kotlin.math.abs

/**
 * Gboard-style Recent Photos & Screenshots panel inside the keyboard (`[🖼 图片/截图]`).
 * Supports filtering between "全部最近图片" and "仅看截图", smooth horizontal/vertical grid
 * scrolling, and one-tap direct `commitContent` image sending.
 */
@SuppressLint("ViewConstructor")
class ImagePanel(
    context: Context,
    private val imageStore: RecentImageStore,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onImageSelected(item: RecentImageStore.ImageItem)
        fun onRequestImagePermission()
        fun onImagePanelClose()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    private var screenshotsOnly = false
    private var items: List<RecentImageStore.ImageItem> = emptyList()
    private var hasPerm = false

    private val tabAllRect = RectF()
    private val tabShotRect = RectF()
    private val closeRect = RectF()
    private val grantBtnRect = RectF()

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var scrollY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedIdx = -1
    private var pressedHeader = 0 // 1=all, 2=shot, 3=close, 4=grant

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val shaderMatrix = Matrix()

    private val headerH get() = 38 * dp
    private val cols = 4
    private val pad get() = 8 * dp
    private val gap get() = 6 * dp

    fun onShown() {
        scrollY = 0f
        scroller.forceFinished(true)
        reload()
    }

    fun reload() {
        hasPerm = imageStore.hasPermission()
        if (!hasPerm) {
            items = emptyList()
            invalidate()
            return
        }
        imageStore.loadRecentAsync(screenshotsOnly = screenshotsOnly, limit = 32) { loaded ->
            items = loaded
            invalidate()
        }
    }

    private fun cellSize(): Float = ((width - 2 * pad - (cols - 1) * gap) / cols).coerceAtLeast(48 * dp)

    private fun maxScroll(): Float {
        if (items.isEmpty()) return 0f
        val rows = (items.size + cols - 1) / cols
        val cell = cellSize()
        val contentH = pad + rows * cell + (rows - 1) * gap + pad
        return maxOf(0f, contentH - (height - headerH))
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val u = minOf(sp, dp * 1.12f)

        // 1) Top header bar: [全部图片] [仅截图] .......... [返回键盘]
        tabAllRect.set(pad, 5 * dp, pad + 78 * dp, headerH - 5 * dp)
        tabShotRect.set(tabAllRect.right + 6 * dp, 5 * dp, tabAllRect.right + 76 * dp, headerH - 5 * dp)
        closeRect.set(width - pad - 68 * dp, 5 * dp, width - pad, headerH - 5 * dp)

        drawPill(c, tabAllRect, active = !screenshotsOnly, down = pressedHeader == 1, label = "最近图片", u = u)
        drawPill(c, tabShotRect, active = screenshotsOnly, down = pressedHeader == 2, label = "仅看截图", u = u)
        drawPill(c, closeRect, active = false, down = pressedHeader == 3, label = "返回", u = u)

        fill.color = theme.divider
        c.drawRect(0f, headerH - 0.5f * dp, width.toFloat(), headerH, fill)

        // 2) Permission prompt if not yet granted
        if (!hasPerm) {
            val cy = headerH + (height - headerH) / 2f
            text.color = theme.text
            text.isFakeBoldText = true
            text.textSize = 15 * u
            c.drawText("开启本地相册读取后，可一键发送最新截图与照片", width / 2f, cy - 22 * dp, text)
            text.isFakeBoldText = false
            text.color = theme.textSecondary
            text.textSize = 12 * u
            c.drawText("完全离线读取本机图库 · 应用无任何联网权限", width / 2f, cy - 2 * dp, text)

            val bw = 148 * dp; val bh = 36 * dp
            grantBtnRect.set(width / 2f - bw / 2f, cy + 12 * dp, width / 2f + bw / 2f, cy + 12 * dp + bh)
            fill.color = if (pressedHeader == 4) theme.keyPressed else theme.accent
            c.drawRoundRect(grantBtnRect, bh / 2f, bh / 2f, fill)
            KeyDraw.drawKeyText(c, "授权访问图片", grantBtnRect, 14 * u, theme.accentText, text, dp, bold = true)
            return
        }

        if (items.isEmpty()) {
            val cy = headerH + (height - headerH) / 2f
            text.color = theme.textSecondary
            text.isFakeBoldText = false
            text.textSize = 14 * u
            c.drawText(if (screenshotsOnly) "暂无最近截图" else "暂无最近图片", width / 2f, cy, text)
            return
        }

        // 3) 4-column rounded thumbnail grid
        val cell = cellSize()
        c.save()
        c.clipRect(0f, headerH, width.toFloat(), height.toFloat())
        val rc = RectF()
        for (i in items.indices) {
            val r = i / cols
            val col = i % cols
            val l = pad + col * (cell + gap)
            val t = headerH + pad + r * (cell + gap) - scrollY
            if (t + cell < headerH || t > height) continue
            rc.set(l, t, l + cell, t + cell)

            val item = items[i]
            val bmp = imageStore.cachedThumb(item.id)
            if (bmp != null && !bmp.isRecycled) {
                val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val scale = maxOf(rc.width() / bmp.width, rc.height() / bmp.height)
                shaderMatrix.reset()
                shaderMatrix.setScale(scale, scale)
                shaderMatrix.postTranslate(
                    rc.left + (rc.width() - bmp.width * scale) * 0.5f,
                    rc.top + (rc.height() - bmp.height * scale) * 0.5f,
                )
                shader.setLocalMatrix(shaderMatrix)
                bmpPaint.shader = shader
                c.drawRoundRect(rc, 8 * dp, 8 * dp, bmpPaint)
                bmpPaint.shader = null
            } else {
                fill.color = theme.funcKey
                c.drawRoundRect(rc, 8 * dp, 8 * dp, fill)
                imageStore.requestThumb(item, 180) { invalidate() }
            }

            if (item.isScreenshot) {
                val tagRc = RectF(rc.left + 4 * dp, rc.bottom - 18 * dp, rc.left + 34 * dp, rc.bottom - 4 * dp)
                fill.color = 0xAA000000.toInt()
                c.drawRoundRect(tagRc, 4 * dp, 4 * dp, fill)
                KeyDraw.drawKeyText(c, "截图", tagRc, 9.5f * u, 0xFFFFFFFF.toInt(), text, dp, padH = 2 * dp)
            }

            if (i == pressedIdx) {
                fill.color = 0x44000000
                c.drawRoundRect(rc, 8 * dp, 8 * dp, fill)
            }
        }
        c.restore()
    }

    private fun drawPill(c: Canvas, rc: RectF, active: Boolean, down: Boolean, label: String, u: Float) {
        fill.color = when {
            active -> theme.accent
            down -> theme.keyPressed
            else -> theme.funcKey
        }
        c.drawRoundRect(rc, rc.height() / 2f, rc.height() / 2f, fill)
        val fg = if (active) theme.accentText else theme.text
        KeyDraw.drawKeyText(c, label, rc, 12.5f * u, fg, text, dp, padH = 6 * dp, bold = active)
    }

    private fun hitHeader(x: Float, y: Float): Int {
        if (!hasPerm && grantBtnRect.contains(x, y)) return 4
        if (y > headerH) return 0
        if (tabAllRect.contains(x, y)) return 1
        if (tabShotRect.contains(x, y)) return 2
        if (closeRect.contains(x, y)) return 3
        return 0
    }

    private fun hitCell(x: Float, y: Float): Int {
        if (!hasPerm || y <= headerH || items.isEmpty()) return -1
        val cell = cellSize()
        val col = ((x - pad) / (cell + gap)).toInt()
        val row = ((y - headerH - pad + scrollY) / (cell + gap)).toInt()
        if (col !in 0 until cols || row < 0) return -1
        val idx = row * cols + col
        return if (idx in items.indices) idx else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastY = e.y; dragging = false
                pressedHeader = hitHeader(e.x, e.y)
                pressedIdx = if (pressedHeader == 0) hitCell(e.x, e.y) else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hasPerm && e.y > headerH && abs(e.y - downY) > slop && maxScroll() > 0f) {
                    dragging = true; pressedIdx = -1; pressedHeader = 0
                }
                if (dragging) {
                    scrollY = (scrollY - (e.y - lastY)).coerceIn(0f, maxScroll())
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    scroller.fling(0, scrollY.toInt(), 0, -velocity!!.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                    postInvalidateOnAnimation()
                } else {
                    val h = hitHeader(e.x, e.y)
                    if (h != 0 && h == pressedHeader) {
                        when (h) {
                            1 -> if (screenshotsOnly) { screenshotsOnly = false; scrollY = 0f; reload() }
                            2 -> if (!screenshotsOnly) { screenshotsOnly = true; scrollY = 0f; reload() }
                            3 -> listener.onImagePanelClose()
                            4 -> listener.onRequestImagePermission()
                        }
                    } else {
                        val idx = hitCell(e.x, e.y)
                        if (idx >= 0 && idx == pressedIdx) listener.onImageSelected(items[idx])
                    }
                }
                pressedHeader = 0; pressedIdx = -1; dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedHeader = 0; pressedIdx = -1; dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }
}
