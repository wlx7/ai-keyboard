package app.aikb.ime.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import app.aikb.ime.engine.OfflineAsrEngine

/**
 * 100% built-in offline neural voice input panel (`sherpa-onnx` streaming Zipformer int8).
 * Works across all domestic and international Android devices with ZERO internet permission
 * and ZERO dependency on Google or vendor cloud speech services.
 */
@SuppressLint("ViewConstructor")
class VoicePanel(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onVoiceCommit(text: String)
        fun onVoicePartial(text: String)
        fun onVoiceDelete()
        fun onVoiceClose()
        fun onVoiceRequestPermission()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    private var listening = false
    private var rmsLevel = 0f
    private var statusText = "点击麦克风开始说话"
    private var previewText = ""

    private val micRect = RectF()
    private val backRect = RectF()
    private val commaRect = RectF()
    private val periodRect = RectF()
    private val questRect = RectF()
    private val delRect = RectF()

    private var pressedBtn = 0 // 1=mic, 2=back, 3=comma, 4=del, 5=period, 6=quest

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val asrCallback = object : OfflineAsrEngine.Callback {
        override fun onState(listening: Boolean, status: String) {
            this@VoicePanel.listening = listening
            this@VoicePanel.statusText = status
            invalidate()
        }

        override fun onRms(level: Float) {
            this@VoicePanel.rmsLevel = level
            if (listening) invalidate()
        }

        override fun onPartial(text: String) {
            previewText = text
            listener.onVoicePartial(text)
            invalidate()
        }

        override fun onFinal(text: String) {
            previewText = ""
            listener.onVoicePartial("")
            if (text.isNotEmpty()) {
                listener.onVoiceCommit(text)
                statusText = "已上屏，请继续说话…"
            }
            invalidate()
        }
    }

    fun onShown() {
        previewText = ""
        rmsLevel = 0f
        if (!hasMicPermission()) {
            listening = false
            statusText = "请授予麦克风录音权限（纯本地离线识别，不联网）"
            invalidate()
            listener.onVoiceRequestPermission()
        } else {
            startListening()
        }
    }

    fun stopAndRelease() {
        listening = false
        rmsLevel = 0f
        previewText = ""
        OfflineAsrEngine.stop()
        listener.onVoicePartial("")
        invalidate()
    }

    private fun startListening() {
        if (!hasMicPermission()) {
            listener.onVoiceRequestPermission()
            return
        }
        previewText = ""
        OfflineAsrEngine.start(context, asrCallback)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h * 0.48f
        val r = 32 * dp
        micRect.set(cx - r, cy - r, cx + r, cy + r)

        val barTop = h - 44 * dp
        val barBot = h - 6 * dp
        backRect.set(10 * dp, barTop, 88 * dp, barBot)
        delRect.set(w - 72 * dp, barTop, w - 10 * dp, barBot)

        // Quick punctuation pills in the middle (`，`  `。`  `？`)
        val pw = 42 * dp
        val gap = 8 * dp
        val totalP = pw * 3 + gap * 2
        val pStart = (w - totalP) / 2f
        commaRect.set(pStart, barTop, pStart + pw, barBot)
        periodRect.set(pStart + pw + gap, barTop, pStart + pw * 2 + gap, barBot)
        questRect.set(pStart + (pw + gap) * 2, barTop, pStart + pw * 3 + gap * 2, barBot)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val u = minOf(sp, dp * 1.12f)
        val cx = width / 2f

        // Status / live streaming transcript line
        text.textSize = 14.5f * u
        text.color = if (previewText.isNotEmpty()) theme.text else theme.textSecondary
        val shown = previewText.ifEmpty { statusText }
        val maxW = width - 32 * dp
        val line = if (text.measureText(shown) > maxW) "…" + shown.takeLast(22) else shown
        c.drawText(line, cx, 28 * dp, text)

        // Pulsing audio ring + mic button
        val mcx = micRect.centerX()
        val mcy = micRect.centerY()
        val baseR = micRect.width() / 2f
        if (listening) {
            fill.color = (theme.accent and 0x00FFFFFF) or 0x28000000
            c.drawCircle(mcx, mcy, baseR * (1.16f + 0.36f * rmsLevel), fill)
        }
        fill.color = if (listening) theme.accent else if (pressedBtn == 1) theme.keyPressed else theme.key
        c.drawCircle(mcx, mcy, baseR, fill)
        val micFg = if (listening) theme.accentText else theme.accent
        KeyDraw.drawMicIcon(c, mcx, mcy, 28 * dp, micFg, stroke, dp)

        // Bottom controls: 返回键盘 | ， 。 ？ | ⌫
        drawSmallBtn(c, backRect, "返回键盘", pressedBtn == 2, 13.5f * u)
        drawSmallBtn(c, commaRect, "，", pressedBtn == 3, 16f * u)
        drawSmallBtn(c, periodRect, "。", pressedBtn == 5, 16f * u)
        drawSmallBtn(c, questRect, "？", pressedBtn == 6, 16f * u)
        drawSmallBtn(c, delRect, "⌫", pressedBtn == 4, 16f * u)
    }

    private fun drawSmallBtn(c: Canvas, r: RectF, label: String, pressed: Boolean, sizePx: Float) {
        fill.color = if (pressed) theme.funcKeyPressed else theme.funcKey
        c.drawRoundRect(r, 7 * dp, 7 * dp, fill)
        KeyDraw.drawKeyText(c, label, r, sizePx, theme.text, text, dp)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedBtn = hit(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val up = hit(e.x, e.y)
                if (up == pressedBtn) {
                    when (up) {
                        1 -> if (listening) {
                            OfflineAsrEngine.stop()
                            listening = false
                            statusText = "已暂停，轻触麦克风继续说话"
                            invalidate()
                        } else startListening()
                        2 -> { stopAndRelease(); listener.onVoiceClose() }
                        3 -> { flushAndCommitPunct("，") }
                        5 -> { flushAndCommitPunct("。") }
                        6 -> { flushAndCommitPunct("？") }
                        4 -> listener.onVoiceDelete()
                    }
                }
                pressedBtn = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedBtn = 0; invalidate() }
        }
        return true
    }

    private fun flushAndCommitPunct(p: String) {
        if (previewText.isNotEmpty()) {
            val pending = previewText
            previewText = ""
            listener.onVoicePartial("")
            listener.onVoiceCommit(pending + p)
        } else {
            listener.onVoiceCommit(p)
        }
    }

    private fun hit(x: Float, y: Float): Int = when {
        micRect.contains(x, y) -> 1
        backRect.contains(x, y) -> 2
        commaRect.contains(x, y) -> 3
        periodRect.contains(x, y) -> 5
        questRect.contains(x, y) -> 6
        delRect.contains(x, y) -> 4
        else -> 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAndRelease()
    }
}
