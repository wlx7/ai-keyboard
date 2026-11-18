package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * WeChat-Keyboard-inspired quick feature drawer opened from the single `[⊞]` button on the top bar.
 * Combines:
 *   1) Input mode pill switcher (全拼 / 九宫格 / 双拼 / 手写 / 五笔·仓颉)
 *   2) 2×3 clean feature cards (剪贴板 / 语音输入 / 简繁切换 / 切换输入法 / 设置 / 收起键盘)
 *   3) Compact bottom text-editing strip (← / → / 全选 / 复制 / 粘贴 / ⌫)
 */
@SuppressLint("ViewConstructor")
class ToolPanel(context: Context, private val listener: Listener) : View(context) {

    enum class InputMode { QUANPIN, T9, SHUANGPIN, HANDWRITING, SHAPE }
    enum class EditAction { LEFT, RIGHT, SELECT_ALL, COPY, PASTE, DELETE }
    enum class Tile { CLIPBOARD, VOICE, TRADITIONAL, SWITCH_IME, SETTINGS, HIDE }

    interface Listener {
        fun onSelectInputMode(mode: InputMode)
        fun onToolTile(tile: Tile)
        fun onEditAction(action: EditAction)
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    var activeMode: InputMode = InputMode.QUANPIN
        set(v) { field = v; invalidate() }
    var shapeLabel: String = "五笔"
        set(v) { field = v; invalidate() }
    var traditional: Boolean = false
        set(v) { field = v; invalidate() }
    var clipCount: Int = 0
        set(v) { field = v; invalidate() }

    private val modes = InputMode.entries
    private val tiles = Tile.entries
    private val edits = EditAction.entries

    private val modeRects = Array(modes.size) { RectF() }
    private val tileRects = Array(tiles.size) { RectF() }
    private val editRects = Array(edits.size) { RectF() }

    private var pressedGroup = 0 // 1=mode, 2=tile, 3=edit
    private var pressedIndex = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val side = 8 * dp
        val gap = 6 * dp
        val topY = 6 * dp
        val modeH = (h * 0.165f).coerceIn(32 * dp, 40 * dp)
        val editH = (h * 0.165f).coerceIn(32 * dp, 40 * dp)
        val editTop = h - 5 * dp - editH

        // 1) Top input mode switcher pills
        val mw = (w - 2 * side - (modes.size - 1) * gap) / modes.size
        for (i in modes.indices) {
            val l = side + i * (mw + gap)
            modeRects[i].set(l, topY, l + mw, topY + modeH)
        }

        // 2) Middle 2x3 feature cards
        val gridTop = topY + modeH + 7 * dp
        val gridBot = editTop - 7 * dp
        val tileH = ((gridBot - gridTop - gap) / 2f).coerceAtLeast(36 * dp)
        val tileW = (w - 2 * side - 2 * gap) / 3f
        for (i in tiles.indices) {
            val r = i / 3
            val c = i % 3
            val l = side + c * (tileW + gap)
            val t = gridTop + r * (tileH + gap)
            tileRects[i].set(l, t, l + tileW, t + tileH)
        }

        // 3) Bottom quick cursor/clipboard edit bar
        val ew = (w - 2 * side - (edits.size - 1) * gap) / edits.size
        for (i in edits.indices) {
            val l = side + i * (ew + gap)
            editRects[i].set(l, editTop, l + ew, editTop + editH)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val u = minOf(sp, dp * 1.12f)

        // 1) Mode pills
        modes.forEachIndexed { i, m ->
            val rc = modeRects[i]
            val selected = m == activeMode
            val down = pressedGroup == 1 && pressedIndex == i
            fill.color = when {
                selected -> theme.accent
                down -> theme.keyPressed
                else -> theme.funcKey
            }
            c.drawRoundRect(rc, rc.height() / 2f, rc.height() / 2f, fill)
            val label = when (m) {
                InputMode.QUANPIN -> "全拼"
                InputMode.T9 -> "九宫格"
                InputMode.SHUANGPIN -> "双拼"
                InputMode.HANDWRITING -> "手写"
                InputMode.SHAPE -> shapeLabel
            }
            val fg = if (selected) theme.accentText else theme.text
            KeyDraw.drawKeyText(c, label, rc, 13.5f * u, fg, text, dp, padH = 5 * dp, bold = selected)
        }

        // 2) Feature tiles (2x3)
        tiles.forEachIndexed { i, tile ->
            val rc = tileRects[i]
            val down = pressedGroup == 2 && pressedIndex == i
            fill.color = theme.keyShadow
            c.drawRoundRect(rc.left, rc.top + 1f * dp, rc.right, rc.bottom + 1f * dp, 9 * dp, 9 * dp, fill)
            fill.color = if (down) theme.keyPressed else theme.key
            c.drawRoundRect(rc, 9 * dp, 9 * dp, fill)

            val iconX = rc.left + 21 * dp
            val cy = rc.centerY()
            val iconColor = if (tile == Tile.TRADITIONAL && traditional) theme.accent else theme.text

            when (tile) {
                Tile.CLIPBOARD -> KeyDraw.drawClipboardIcon(c, iconX, cy, 20 * dp, iconColor, stroke, dp)
                Tile.VOICE -> KeyDraw.drawMicIcon(c, iconX, cy, 20 * dp, iconColor, stroke, dp)
                Tile.TRADITIONAL -> {
                    fill.color = if (traditional) theme.accent else theme.funcKey
                    c.drawRoundRect(iconX - 10 * dp, cy - 10 * dp, iconX + 10 * dp, cy + 10 * dp, 5 * dp, 5 * dp, fill)
                    val badgeRc = RectF(iconX - 10 * dp, cy - 10 * dp, iconX + 10 * dp, cy + 10 * dp)
                    KeyDraw.drawKeyText(
                        c, if (traditional) "繁" else "简", badgeRc, 12.5f * u,
                        if (traditional) theme.accentText else theme.text, text, dp, padH = 1 * dp, bold = true
                    )
                }
                Tile.SWITCH_IME -> KeyDraw.drawGlobeIcon(c, iconX, cy, 20 * dp, iconColor, stroke, dp)
                Tile.SETTINGS -> KeyDraw.drawGearIcon(c, iconX, cy, 20 * dp, iconColor, stroke, dp)
                Tile.HIDE -> KeyDraw.drawChevron(c, iconX, cy, 6 * dp, 1, iconColor, stroke, dp)
            }

            val (title, sub) = when (tile) {
                Tile.CLIPBOARD -> "剪贴板" to (if (clipCount > 0) "$clipCount 条 · 1h清理" else "1h自动清理")
                Tile.VOICE -> "语音输入" to "离线优先"
                Tile.TRADITIONAL -> (if (traditional) "繁体输出" else "简体输出") to (if (traditional) "已开启" else "点击切换")
                Tile.SWITCH_IME -> "切换输入法" to "系统列表"
                Tile.SETTINGS -> "设置" to "方案与模型"
                Tile.HIDE -> "收起键盘" to "隐藏面板"
            }

            val textLeft = rc.left + 36 * dp
            val maxW = (rc.right - textLeft - 5 * dp).coerceAtLeast(20 * dp)
            text.textAlign = Paint.Align.LEFT
            text.isFakeBoldText = true
            text.textSize = 13.2f * u
            if (text.measureText(title) > maxW) text.textSize *= (maxW / text.measureText(title))
            text.color = theme.text
            c.drawText(title, textLeft, cy - 2.5f * dp, text)

            text.isFakeBoldText = false
            text.textSize = 10.5f * u
            if (text.measureText(sub) > maxW) text.textSize *= (maxW / text.measureText(sub))
            text.color = if (tile == Tile.TRADITIONAL && traditional) theme.accent else theme.textSecondary
            c.drawText(sub, textLeft, cy + 12.5f * dp, text)
            text.textAlign = Paint.Align.CENTER
        }

        // 3) Quick edit strip
        edits.forEachIndexed { i, act ->
            val rc = editRects[i]
            val down = pressedGroup == 3 && pressedIndex == i
            fill.color = if (down) theme.funcKeyPressed else theme.funcKey
            c.drawRoundRect(rc, 6.5f * dp, 6.5f * dp, fill)
            val label = when (act) {
                EditAction.LEFT -> "←"
                EditAction.RIGHT -> "→"
                EditAction.SELECT_ALL -> "全选"
                EditAction.COPY -> "复制"
                EditAction.PASTE -> "粘贴"
                EditAction.DELETE -> "⌫"
            }
            KeyDraw.drawKeyText(c, label, rc, 13.5f * u, theme.text, text, dp, padH = 3 * dp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (g, idx) = hit(e.x, e.y)
                pressedGroup = g; pressedIndex = idx
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val (g, idx) = hit(e.x, e.y)
                if (g != pressedGroup || idx != pressedIndex) {
                    pressedGroup = 0; pressedIndex = -1
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val (g, idx) = hit(e.x, e.y)
                if (g == pressedGroup && idx == pressedIndex && idx >= 0) {
                    when (g) {
                        1 -> listener.onSelectInputMode(modes[idx])
                        2 -> listener.onToolTile(tiles[idx])
                        3 -> listener.onEditAction(edits[idx])
                    }
                }
                pressedGroup = 0; pressedIndex = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedGroup = 0; pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Pair<Int, Int> {
        modeRects.forEachIndexed { i, r -> if (r.contains(x, y)) return 1 to i }
        tileRects.forEachIndexed { i, r -> if (r.contains(x, y)) return 2 to i }
        editRects.forEachIndexed { i, r -> if (r.contains(x, y)) return 3 to i }
        return 0 to -1
    }
}
