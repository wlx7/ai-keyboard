package app.aikb.ime.keyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Offline-first voice input panel embedded inside the keyboard stack.
 * Uses Android's system SpeechRecognizer with EXTRA_PREFER_OFFLINE = true (the app itself holds
 * NO INTERNET permission). Also offers one-tap handoff to an installed system voice IME if present.
 */
@SuppressLint("ViewConstructor")
class VoicePanel(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onVoiceCommit(text: String)
        fun onVoicePartial(text: String)
        fun onVoiceDelete()
        fun onVoiceClose()
        fun onVoiceRequestPermission()
        fun hasSystemVoiceIme(): Boolean
        fun onSwitchSystemVoiceIme()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    var theme: KbTheme = KbTheme.of(context)
        set(v) { field = v; invalidate() }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var rmsLevel = 0f
    private var statusText = "点击麦克风开始说话"
    private var previewText = ""

    private val micRect = RectF()
    private val backRect = RectF()
    private val sysVoiceRect = RectF()
    private val delRect = RectF()

    private var pressedBtn = 0 // 1=mic, 2=back, 3=sysVoice, 4=del

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun onShown() {
        previewText = ""
        rmsLevel = 0f
        if (!hasMicPermission()) {
            listening = false
            statusText = "点击下方麦克风授权录音（本应用无联网权限）"
            invalidate()
        } else {
            startListening()
        }
    }

    fun stopAndRelease() {
        listening = false
        rmsLevel = 0f
        listener.onVoicePartial("")
        runCatching {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
        invalidate()
    }

    private fun startListening() {
        if (!hasMicPermission()) {
            listener.onVoiceRequestPermission()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listening = false
            statusText = if (listener.hasSystemVoiceIme()) {
                "未检测到系统语音服务，可点下方切换系统语音输入"
            } else {
                "当前系统未安装本地语音识别服务"
            }
            invalidate()
            return
        }
        runCatching {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recListener)
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            previewText = ""
            statusText = "正在聆听（离线优先）…"
            listening = true
            recognizer?.startListening(intent)
        }.onFailure {
            listening = false
            statusText = "语音引擎启动失败，请检查系统语音服务"
        }
        invalidate()
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            statusText = "请说话…"
            invalidate()
        }
        override fun onBeginningOfSpeech() {
            statusText = "正在识别…"
            invalidate()
        }
        override fun onRmsChanged(rmsdB: Float) {
            rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            invalidate()
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
            rmsLevel = 0f
            statusText = "正在处理…"
            invalidate()
        }
        override fun onError(error: Int) {
            listening = false
            rmsLevel = 0f
            listener.onVoicePartial("")
            statusText = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未听清，轻触麦克风重试"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限，轻触授权"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "系统语音需下载离线语音包（设置 → 系统语音）"
                else -> "识别已暂停，轻触麦克风继续"
            }
            invalidate()
        }
        override fun onResults(results: Bundle?) {
            listening = false
            rmsLevel = 0f
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val out = list?.firstOrNull()?.trim().orEmpty()
            previewText = ""
            listener.onVoicePartial("")
            if (out.isNotEmpty()) {
                listener.onVoiceCommit(out)
                statusText = "已输入，轻触麦克风继续说话"
            } else {
                statusText = "轻触麦克风继续说话"
            }
            invalidate()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (part.isNotEmpty()) {
                previewText = part
                listener.onVoicePartial(part)
                invalidate()
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h * 0.50f
        val r = 32 * dp
        micRect.set(cx - r, cy - r, cx + r, cy + r)

        val barTop = h - 44 * dp
        val barBot = h - 6 * dp
        backRect.set(10 * dp, barTop, 88 * dp, barBot)
        sysVoiceRect.set(cx - 58 * dp, barTop, cx + 58 * dp, barBot)
        delRect.set(w - 76 * dp, barTop, w - 10 * dp, barBot)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(theme.background)
        val u = minOf(sp, dp * 1.12f)
        val cx = width / 2f

        // Status / live transcript line
        text.textSize = 14.5f * u
        text.color = if (previewText.isNotEmpty()) theme.text else theme.textSecondary
        val shown = previewText.ifEmpty { statusText }
        val maxW = width - 32 * dp
        val line = if (text.measureText(shown) > maxW) "…" + shown.takeLast(22) else shown
        c.drawText(line, cx, 28 * dp, text)

        // Pulsing ring + mic button
        val mcx = micRect.centerX()
        val mcy = micRect.centerY()
        val baseR = micRect.width() / 2f
        if (listening) {
            fill.color = (theme.accent and 0x00FFFFFF) or 0x26000000
            c.drawCircle(mcx, mcy, baseR * (1.18f + 0.32f * rmsLevel), fill)
        }
        fill.color = if (listening) theme.accent else if (pressedBtn == 1) theme.keyPressed else theme.key
        c.drawCircle(mcx, mcy, baseR, fill)
        val micFg = if (listening) theme.accentText else theme.accent
        KeyDraw.drawMicIcon(c, mcx, mcy, 28 * dp, micFg, stroke, dp)

        // Bottom controls: 返回键盘 | [系统语音] | ⌫
        fill.color = if (pressedBtn == 2) theme.funcKeyPressed else theme.funcKey
        c.drawRoundRect(backRect, 7 * dp, 7 * dp, fill)
        KeyDraw.drawKeyText(c, "返回键盘", backRect, 13.5f * u, theme.text, text, dp)

        if (listener.hasSystemVoiceIme()) {
            fill.color = if (pressedBtn == 3) theme.funcKeyPressed else theme.funcKey
            c.drawRoundRect(sysVoiceRect, 7 * dp, 7 * dp, fill)
            KeyDraw.drawKeyText(c, "系统语音输入", sysVoiceRect, 13.0f * u, theme.text, text, dp)
        }

        fill.color = if (pressedBtn == 4) theme.funcKeyPressed else theme.funcKey
        c.drawRoundRect(delRect, 7 * dp, 7 * dp, fill)
        KeyDraw.drawKeyText(c, "⌫", delRect, 16 * u, theme.text, text, dp)
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
                            runCatching { recognizer?.stopListening() }
                            listening = false
                            invalidate()
                        } else startListening()
                        2 -> { stopAndRelease(); listener.onVoiceClose() }
                        3 -> if (listener.hasSystemVoiceIme()) { stopAndRelease(); listener.onSwitchSystemVoiceIme() }
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

    private fun hit(x: Float, y: Float): Int = when {
        micRect.contains(x, y) -> 1
        backRect.contains(x, y) -> 2
        listener.hasSystemVoiceIme() && sysVoiceRect.contains(x, y) -> 3
        delRect.contains(x, y) -> 4
        else -> 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAndRelease()
    }
}
