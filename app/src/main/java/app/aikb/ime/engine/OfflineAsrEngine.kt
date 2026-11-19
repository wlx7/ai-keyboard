package app.aikb.ime.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlin.math.sqrt

/**
 * 100% built-in offline streaming speech recognition engine powered by `sherpa-onnx`
 * (`streaming-zipformer-small-ctc-zh-int8`, bundled in `assets/asr/`).
 *
 *   - Requires ZERO internet permission and ZERO third-party Google/vendor speech services.
 *   - Works identically across all domestic and international Android ROMs in Airplane Mode.
 *   - Streams 16 kHz mono PCM from [AudioRecord], decodes partial results in real time, and
 *     automatically commits utterances on natural speech pauses.
 */
object OfflineAsrEngine {
    private const val TAG = "OfflineAsr"
    private const val SAMPLE_RATE = 16000

    interface Callback {
        fun onState(listening: Boolean, status: String)
        fun onRms(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
    }

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var running = false
    private var recordThread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    val isListening: Boolean get() = running

    @Synchronized
    private fun ensureRecognizer(context: Context): OnlineRecognizer? {
        recognizer?.let { return it }
        return runCatching {
            val modelCfg = OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "asr/model.int8.onnx"),
                tokens = "asr/tokens.txt",
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                debug = false,
                provider = "cpu",
            )
            val endpointCfg = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.15f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            )
            val cfg = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelCfg,
                endpointConfig = endpointCfg,
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            OnlineRecognizer(assetManager = context.applicationContext.assets, config = cfg).also {
                recognizer = it
                Log.i(TAG, "Offline sherpa-onnx recognizer initialized")
            }
        }.onFailure {
            Log.e(TAG, "Failed to init offline ASR", it)
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, callback: Callback) {
        stop()
        running = true
        val app = context.applicationContext
        callback.onState(false, "正在准备本地离线语音模型…")
        recordThread = Thread({
            val rec = ensureRecognizer(app)
            if (rec == null) {
                running = false
                main.post { callback.onState(false, "离线语音模型加载失败") }
                return@Thread
            }
            if (!running) return@Thread

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                running = false
                main.post { callback.onState(false, "无法打开麦克风音频流") }
                return@Thread
            }

            val bufBytes = maxOf(minBuf * 2, SAMPLE_RATE / 5 * 2)
            val audioRecord = createAudioRecord(bufBytes)
            if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                running = false
                main.post { callback.onState(false, "麦克风被占用或无录音权限") }
                return@Thread
            }

            val stream = rec.createStream("")
            val pcmShort = ShortArray(SAMPLE_RATE / 10) // 100ms chunks
            val pcmFloat = FloatArray(pcmShort.size)
            var lastPartial = ""

            try {
                audioRecord.startRecording()
                main.post { if (running) callback.onState(true, "请说话（纯本地离线识别）…") }

                while (running) {
                    val n = audioRecord.read(pcmShort, 0, pcmShort.size)
                    if (n <= 0) continue

                    var sumSq = 0.0
                    for (i in 0 until n) {
                        val f = pcmShort[i] / 32768.0f
                        pcmFloat[i] = f
                        sumSq += f * f
                    }
                    val rms = sqrt(sumSq / n).toFloat()
                    val normLevel = (rms * 8.5f).coerceIn(0f, 1f)
                    main.post { if (running) callback.onRms(normLevel) }

                    stream.acceptWaveform(pcmFloat, SAMPLE_RATE)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }

                    val text = cleanTranscript(rec.getResult(stream).text)
                    val isEnd = rec.isEndpoint(stream)

                    if (text.isNotEmpty() && text != lastPartial && !isEnd) {
                        lastPartial = text
                        val outPart = maybeTraditional(app, text)
                        main.post { if (running) callback.onPartial(outPart) }
                    }

                    if (isEnd) {
                        if (text.isNotEmpty()) {
                            val outFinal = maybeTraditional(app, text)
                            main.post { callback.onFinal(outFinal) }
                        }
                        lastPartial = ""
                        rec.reset(stream)
                    }
                }

                // Flush any remaining partial speech when user manually taps stop
                stream.inputFinished()
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                val tail = cleanTranscript(rec.getResult(stream).text)
                if (tail.isNotEmpty()) {
                    val outTail = maybeTraditional(app, tail)
                    main.post { callback.onFinal(outTail) }
                } else if (lastPartial.isNotEmpty()) {
                    main.post { callback.onPartial("") }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Streaming ASR error", t)
                main.post { callback.onState(false, "语音识别中断，轻触麦克风重试") }
            } finally {
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                runCatching { stream.release() }
                running = false
                main.post { callback.onRms(0f) }
            }
        }, "offline-asr").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufBytes: Int): AudioRecord? {
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val ar = runCatching {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            }.getOrNull()
            if (ar != null && ar.state == AudioRecord.STATE_INITIALIZED) return ar
            ar?.release()
        }
        return null
    }

    private fun cleanTranscript(raw: String): String {
        if (raw.isBlank()) return ""
        // Collapse extraneous spaces between CJK characters while keeping spaces between English words
        val trimmed = raw.trim()
        val sb = StringBuilder(trimmed.length)
        for (i in trimmed.indices) {
            val c = trimmed[i]
            if (c == ' ' && i > 0 && i + 1 < trimmed.length) {
                val prevCjk = ChinesePredictor.isCjk(trimmed[i - 1])
                val nextCjk = ChinesePredictor.isCjk(trimmed[i + 1])
                if (prevCjk && nextCjk) continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun maybeTraditional(context: Context, text: String): String =
        if (RimeEngine.traditional) HandwritingEngine.toTraditional(context, text) else text

    @Synchronized
    fun stop() {
        running = false
        val t = recordThread
        recordThread = null
        if (t != null && t != Thread.currentThread()) {
            runCatching { t.join(350) }
        }
    }

    @Synchronized
    fun unload() {
        stop()
        runCatching { recognizer?.release() }
        recognizer = null
    }
}
