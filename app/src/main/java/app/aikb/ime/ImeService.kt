package app.aikb.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.aikb.ime.engine.AiRanker
import app.aikb.ime.engine.ChinesePredictor
import app.aikb.ime.engine.ClipboardStore
import app.aikb.ime.engine.EnglishDictionary
import app.aikb.ime.engine.EnglishEngine
import app.aikb.ime.engine.EnglishResult
import app.aikb.ime.engine.HandwritingEngine
import app.aikb.ime.engine.OfflineAsrEngine
import app.aikb.ime.engine.Rime
import app.aikb.ime.engine.RimeEngine
import app.aikb.ime.engine.T9
import app.aikb.ime.engine.UserHistory
import app.aikb.ime.keyboard.CandidateBar
import app.aikb.ime.keyboard.CandidatePanel
import app.aikb.ime.keyboard.ClipboardPanel
import app.aikb.ime.keyboard.EmojiPanel
import app.aikb.ime.keyboard.HandwritingView
import app.aikb.ime.keyboard.KbTheme
import app.aikb.ime.keyboard.Key
import app.aikb.ime.keyboard.KeyType
import app.aikb.ime.keyboard.KeyboardView
import app.aikb.ime.keyboard.Layout
import app.aikb.ime.keyboard.Layouts
import app.aikb.ime.keyboard.Page
import app.aikb.ime.keyboard.PreeditBar
import app.aikb.ime.keyboard.PreviewOverlay
import app.aikb.ime.keyboard.ShiftState
import app.aikb.ime.keyboard.T9View
import app.aikb.ime.keyboard.ToolPanel
import app.aikb.ime.keyboard.VoicePanel
import app.aikb.ime.ui.SetupActivity

class ImeService : InputMethodService(),
    KeyboardView.Listener,
    EmojiPanel.Listener,
    T9View.Listener,
    HandwritingView.Listener,
    ToolPanel.Listener,
    ClipboardPanel.Listener,
    VoicePanel.Listener {

    private enum class Overlay { NONE, EMOJI, CANDIDATES, TOOLS, CLIPBOARD, VOICE }

    private lateinit var prefs: SharedPreferences
    private lateinit var history: UserHistory
    private lateinit var clipboardStore: ClipboardStore
    private var sysClipboard: ClipboardManager? = null
    private var english: EnglishEngine? = null
    private var zhPredictor: ChinesePredictor? = null

    private lateinit var root: FrameLayout
    private lateinit var preeditBar: PreeditBar
    private lateinit var keyboard: KeyboardView
    private lateinit var candidates: CandidateBar
    private lateinit var emoji: EmojiPanel
    private lateinit var candidatePanel: CandidatePanel
    private lateinit var toolPanel: ToolPanel
    private lateinit var clipboardPanel: ClipboardPanel
    private lateinit var voicePanel: VoicePanel
    private lateinit var t9: T9View
    private lateinit var handwriting: HandwritingView
    private lateinit var previewOverlay: PreviewOverlay

    private var overlay = Overlay.NONE
    private var handwritingMode = false
    private var hwCandidates: List<String> = emptyList()
    private val hwComposing: Boolean get() = handwritingMode && hwCandidates.isNotEmpty()
    private val hwGen = java.util.concurrent.atomic.AtomicLong()
    private val hwWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "hw").apply { priority = Thread.NORM_PRIORITY }
    }
    private var scene = Scene.of(null)
    private var page = Page.LETTERS
    private var chinese = false
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L

    /** Word being typed (English) or fallback pinyin being typed, shown as composing text. */
    private val composing = StringBuilder()
    private var result: EnglishResult? = null
    private var showNextPredictions = false
    private var showZhPredictions = false
    private var shownCandidates: List<String> = emptyList()
    /** What each candidate commits (may differ from its label, e.g. quoted literal or domain tail). */
    private var candidateCommits: List<String> = emptyList()

    /** Set right after space auto-corrected a word, so backspace can undo it. */
    private var lastAutoCorrect: Pair<String, String>? = null
    /** A word whose auto-correction the user undid; space keeps it as typed. */
    private var rejected: String? = null
    private var lastSpaceAt = 0L
    /** When we last edited the field; selection updates arriving right after are often stale. */
    private var lastEditAt = 0L

    // settings
    private var autoCorrect = true
    private var autoCaps = true
    private var doubleSpacePeriod = true
    private var haptics = true
    private var sound = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val clipTick = object : Runnable {
        override fun run() {
            syncToolbarState()
            uiHandler.postDelayed(this, 15_000L)
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val cm = sysClipboard ?: return@OnPrimaryClipChangedListener
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return@OnPrimaryClipChangedListener
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= 33 && desc?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            return@OnPrimaryClipChangedListener
        }
        if (clip.itemCount > 0) {
            val txt = clip.getItemAt(0).text?.toString()
            if (!txt.isNullOrBlank()) {
                clipboardStore.onCopied(txt)
                if (::clipboardPanel.isInitialized && overlay == Overlay.CLIPBOARD) clipboardPanel.refresh()
                syncToolbarState()
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!::keyboard.isInitialized) return@OnSharedPreferenceChangeListener
        when (key) {
            KEY_HANDWRITING, KEY_SCHEMA, KEY_CHINESE -> {
                if (!scene.forceEnglish) {
                    chinese = prefs.getBoolean(KEY_CHINESE, true)
                    handwritingMode = prefs.getBoolean(KEY_HANDWRITING, false) && chinese && page == Page.LETTERS
                    if (handwritingMode) hwWorker.execute { HandwritingEngine.getOrLoad(applicationContext) }
                    val sc = prefs.getString(KEY_SCHEMA, "rime_ice")
                    if (chinese && !handwritingMode) {
                        RimeEngine.start(this, sc)
                        if (sc != null) RimeEngine.selectSchema(sc)
                    }
                    refreshLayout()
                    updateCandidates()
                }
            }
            KEY_TRADITIONAL -> {
                RimeEngine.traditional = prefs.getBoolean(KEY_TRADITIONAL, false)
                syncToolbarState()
                updateCandidates()
            }
            "clear_history_at" -> {
                history.clear()
                zhPredictor?.clearUserHistory()
                showNextPredictions = false
                showZhPredictions = false
                updateCandidates()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        history = UserHistory(filesDir.resolve("en_user.tsv"))
        clipboardStore = ClipboardStore(filesDir.resolve("clipboard_pinned.txt"))
        sysClipboard = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            runCatching { it.addPrimaryClipChangedListener(clipListener) }
        }
        english = runCatching { EnglishEngine(EnglishDictionary.load(this), history) }.getOrNull()
        zhPredictor = runCatching { ChinesePredictor.load(this, filesDir.resolve("zh_user.tsv")) }.getOrNull()
        RimeEngine.addStateListener(rimeListener)
        RimeEngine.traditional = prefs.getBoolean(KEY_TRADITIONAL, false)
        AiRanker.alpha = prefs.getFloat(KEY_AI_ALPHA, AiRanker.DEFAULT_ALPHA)
        RimeEngine.start(this, prefs.getString(KEY_SCHEMA, null))
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(clipTick)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        runCatching { sysClipboard?.removePrimaryClipChangedListener(clipListener) }
        RimeEngine.removeStateListener(rimeListener)
        OfflineAsrEngine.unload()
        aiWorker.shutdownNow()
        hwWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val theme = KbTheme.of(this)
        previewOverlay = PreviewOverlay(this)
        preeditBar = PreeditBar(this).apply { visibility = View.GONE }
        keyboard = KeyboardView(this, this).also { it.previewOverlay = previewOverlay }
        candidates = CandidateBar(this) { pickCandidate(it) }.also { bar ->
            bar.onExpandToggle = { setOverlay(if (overlay == Overlay.CANDIDATES) Overlay.NONE else Overlay.CANDIDATES) }
            bar.onToolbarAction = { onToolbarAction(it) }
        }
        emoji = EmojiPanel(this, this).apply { visibility = View.GONE }
        candidatePanel = CandidatePanel(this) { pickFromPanel(it) }.apply { visibility = View.GONE }
        toolPanel = ToolPanel(this, this).apply { visibility = View.GONE }
        clipboardPanel = ClipboardPanel(this, clipboardStore, this).apply { visibility = View.GONE }
        voicePanel = VoicePanel(this, this).apply { visibility = View.GONE }
        t9 = T9View(this, this).apply { visibility = View.GONE }
        handwriting = HandwritingView(this, this).apply { visibility = View.GONE }

        val stack = FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(t9, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(handwriting, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(candidatePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(toolPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(clipboardPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(voicePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(preeditBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(candidates, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(previewOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { v, insets ->
                val bottom = if (Build.VERSION.SDK_INT >= 30) maxOf(
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsets.Type.tappableElement()).bottom)
                else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                v.setPadding(0, 0, 0, bottom)
                insets
            }
        }
        applyTheme(theme)
        return root
    }

    private fun applyTheme(t: KbTheme) {
        preeditBar.theme = t
        keyboard.theme = t; candidates.theme = t; emoji.theme = t; previewOverlay.theme = t
        candidatePanel.theme = t; toolPanel.theme = t; clipboardPanel.theme = t; voicePanel.theme = t
        t9.theme = t; handwriting.theme = t
        root.setBackgroundColor(t.background)
        @Suppress("DEPRECATION")
        window.window?.navigationBarColor = t.background
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
        applyTheme(KbTheme.of(this))
        scene = Scene.of(info)
        chinese = when {
            scene.forceEnglish -> false
            scene.preferEnglishOnStart -> false
            else -> prefs.getBoolean(KEY_CHINESE, true)
        }
        handwritingMode = if (scene.forceEnglish || scene.initialPage != Page.LETTERS) false
        else prefs.getBoolean(KEY_HANDWRITING, false) && chinese
        if (handwritingMode) hwWorker.execute { HandwritingEngine.getOrLoad(applicationContext) }
        page = scene.initialPage
        shift = ShiftState.OFF
        composing.clear(); result = null; lastAutoCorrect = null
        showNextPredictions = false; showZhPredictions = false
        preeditBar.text = ""
        hwCandidates = emptyList()
        if (::handwriting.isInitialized) { handwriting.clearCanvas(notify = false); handwriting.hasPendingComposition = false }
        val savedSchema = prefs.getString(KEY_SCHEMA, "rime_ice")
        rememberSchemaPreference(savedSchema)
        RimeEngine.traditional = prefs.getBoolean(KEY_TRADITIONAL, false)
        RimeEngine.start(this, savedSchema)
        RimeEngine.clear(); zh = EMPTY_ZH
        setOverlay(Overlay.NONE)
        refreshLayout()
        updateAutoShift()
        syncToolbarState()
        updateCandidates()
        uiHandler.removeCallbacks(clipTick)
        uiHandler.postDelayed(clipTick, 15_000L)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        uiHandler.removeCallbacks(clipTick)
        if (::voicePanel.isInitialized) voicePanel.stopAndRelease()
        if (::preeditBar.isInitialized) preeditBar.text = ""
        if (::handwriting.isInitialized) { handwriting.clearCanvas(notify = false); handwriting.hasPendingComposition = false }
        hwCandidates = emptyList()
        showZhPredictions = false
        if (composing.isNotEmpty()) { currentInputConnection?.finishComposingText(); composing.clear() }
        if (zhComposing) { RimeEngine.clear(); zh = EMPTY_ZH; currentInputConnection?.finishComposingText() }
        if (::keyboard.isInitialized) keyboard.cancelAll()
        setOverlay(Overlay.NONE)
        history.save()
        zhPredictor?.save()
        prefs.edit().putFloat(KEY_AI_ALPHA, AiRanker.alpha).apply()
        aiSuggestion = null
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val stale = candidatesEnd < 0 && SystemClock.uptimeMillis() - lastEditAt < 300
        if (composing.isNotEmpty() && !stale && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            composing.clear(); result = null; showNextPredictions = false
            updateCandidates()
        }
        if (zhComposing && !stale && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            RimeEngine.clear(); zh = EMPTY_ZH
            currentInputConnection?.finishComposingText()
            updateCandidates()
        }
        if (composing.isEmpty() && !zhComposing) {
            if (!stale && oldSelStart >= 0 && kotlin.math.abs(newSelStart - oldSelStart) > 2) {
                showNextPredictions = false
                showZhPredictions = false
                updateCandidates()
            }
            updateAutoShift()
        }
    }

    private fun loadSettings() {
        autoCorrect = prefs.getBoolean("auto_correct", true)
        autoCaps = prefs.getBoolean("auto_caps", true)
        doubleSpacePeriod = prefs.getBoolean("double_space", true)
        haptics = prefs.getBoolean("haptics", true)
        sound = prefs.getBoolean("sound", false)
    }

    private fun rememberSchemaPreference(schema: String?) {
        if (schema == null) return
        if (schema.startsWith("double_pinyin")) prefs.edit().putString(KEY_LAST_DOUBLE, schema).apply()
        if (schema == "wubi86_jidian" || schema == "cangjie5") prefs.edit().putString(KEY_LAST_SHAPE, schema).apply()
    }

    // ------------------------------------------------------------------ layout & overlays

    private fun currentLayout(): Layout = when (page) {
        Page.LETTERS -> Layouts.letters(scene.bottomRow, chinese,
            if (chinese) RimeEngine.shortName(RimeEngine.currentSchema()) else null)
        Page.SYMBOLS -> Layouts.symbols(scene.bottomRow, chinese)
        Page.SYMBOLS2 -> Layouts.symbols2(scene.bottomRow, chinese)
        Page.NUMBER -> Layouts.numberPad(scene.decimal, scene.signed)
        Page.PHONE -> Layouts.phonePad()
    }

    private fun refreshLayout() {
        keyboard.layout = currentLayout()
        keyboard.chinese = chinese
        keyboard.hints = if (chinese && page == Page.LETTERS) RimeEngine.keyHints(RimeEngine.currentSchema()) else emptyMap()
        keyboard.shift = shift
        val (label, accent) = enterLabel()
        keyboard.enterLabel = label
        keyboard.enterAccent = accent
        t9.enterLabel = label; t9.enterAccent = accent; t9.chinese = chinese
        t9.spaceLabel = "选定"
        handwriting.enterLabel = label; handwriting.enterAccent = accent
        syncToolbarState()
        syncStack()
        updateT9Side()
    }

    private fun enterLabel(): Pair<String, Boolean> {
        val zh = chinese || page == Page.NUMBER || page == Page.PHONE || Locale.zh
        return when (scene.action) {
            EditorInfo.IME_ACTION_GO -> (if (zh) "前往" else "go") to true
            EditorInfo.IME_ACTION_SEARCH -> (if (zh) "搜索" else "search") to true
            EditorInfo.IME_ACTION_SEND -> (if (zh) "发送" else "send") to true
            EditorInfo.IME_ACTION_NEXT -> (if (zh) "下一项" else "next") to false
            EditorInfo.IME_ACTION_PREVIOUS -> (if (zh) "上一项" else "prev") to false
            EditorInfo.IME_ACTION_DONE -> (if (zh) "完成" else "done") to true
            else -> (if (zh) "换行" else "return") to false
        }
    }

    private object Locale {
        val zh: Boolean get() = java.util.Locale.getDefault().language == "zh"
    }

    private fun setShift(s: ShiftState) {
        shift = s
        if (::keyboard.isInitialized) keyboard.shift = s
    }

    private fun updateAutoShift() {
        if (!::keyboard.isInitialized || shift == ShiftState.LOCK) return
        if (page != Page.LETTERS || chinese || !scene.autoCaps || !autoCaps) {
            if (shift == ShiftState.ONCE) setShift(ShiftState.OFF)
            return
        }
        val ic = currentInputConnection ?: return
        val caps = ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0) != 0
        setShift(if (caps) ShiftState.ONCE else ShiftState.OFF)
    }

    /** Extracts the immediately preceding English word in the current sentence for bigram context. */
    private fun prevEnglishWord(): String? {
        val ic = currentInputConnection ?: return null
        val raw = ic.getTextBeforeCursor(48 + composing.length, 0)?.toString() ?: return null
        val before = if (composing.isNotEmpty() && raw.endsWith(composing)) {
            raw.dropLast(composing.length)
        } else raw
        if (before.isEmpty()) return null
        val trimmed = before.trimEnd { it == ' ' || it == '\t' }
        if (trimmed.isEmpty()) return null
        val lastChar = trimmed.last()
        // Stop bigram context across sentence boundaries (. ! ? newline)
        if (lastChar in ".!?\n;:") return null
        var i = trimmed.length - 1
        while (i >= 0 && (trimmed[i].isLetter() || trimmed[i] == '\'' || trimmed[i] == '’')) i--
        val word = trimmed.substring(i + 1)
        return word.takeIf { it.any { c -> c.isLetter() } }
    }

    // ------------------------------------------------------------------ top toolbar & tool panels

    private fun syncToolbarState() {
        if (!::candidates.isInitialized) return
        candidates.handwritingActive = handwritingMode
        candidates.menuActive = overlay in setOf(Overlay.TOOLS, Overlay.CLIPBOARD, Overlay.VOICE)
        candidates.hasClips = clipboardStore.hasAny()
        candidates.freshClip = if (scene.kind == FieldKind.PASSWORD) null else clipboardStore.freshClip()
        if (::toolPanel.isInitialized) {
            val schema = RimeEngine.currentSchema() ?: prefs.getString(KEY_SCHEMA, "rime_ice")
            toolPanel.activeMode = when {
                handwritingMode -> ToolPanel.InputMode.HANDWRITING
                schema == "t9" -> ToolPanel.InputMode.T9
                schema != null && schema.startsWith("double_pinyin") -> ToolPanel.InputMode.SHUANGPIN
                schema == "wubi86_jidian" || schema == "cangjie5" -> ToolPanel.InputMode.SHAPE
                else -> ToolPanel.InputMode.QUANPIN
            }
            val shapeSchema = prefs.getString(KEY_LAST_SHAPE, "wubi86_jidian")
            toolPanel.shapeLabel = if (shapeSchema == "cangjie5") "仓颉" else "五笔"
            toolPanel.traditional = RimeEngine.traditional
            toolPanel.clipCount = clipboardStore.count()
        }
    }

    private fun setOverlay(next: Overlay) {
        if (!::keyboard.isInitialized) { overlay = next; return }
        if (overlay == Overlay.VOICE && next != Overlay.VOICE) voicePanel.stopAndRelease()
        val resolved = if (next == Overlay.CANDIDATES && !zhComposing && !hwComposing) Overlay.NONE else next
        overlay = resolved
        candidates.expanded = resolved == Overlay.CANDIDATES
        val h = keyboard.height.takeIf { it > 0 } ?: (228 * resources.displayMetrics.density).toInt()
        when (resolved) {
            Overlay.CANDIDATES -> {
                candidatePanel.layoutParams = candidatePanel.layoutParams.apply { height = h }
                val list = if (hwComposing) hwCandidates else RimeEngine.snapshot(300).candidates.map { it.text }
                candidatePanel.setItems(list)
            }
            Overlay.EMOJI -> {
                emoji.layoutParams = emoji.layoutParams.apply { height = h }
                emoji.onShown()
            }
            Overlay.TOOLS -> {
                toolPanel.layoutParams = toolPanel.layoutParams.apply { height = h }
                syncToolbarState()
            }
            Overlay.CLIPBOARD -> {
                clipboardPanel.layoutParams = clipboardPanel.layoutParams.apply { height = h }
                clipboardPanel.refresh()
            }
            Overlay.VOICE -> {
                voicePanel.layoutParams = voicePanel.layoutParams.apply { height = h }
                voicePanel.onShown()
            }
            Overlay.NONE -> Unit
        }
        syncToolbarState()
        syncStack()
    }

    private fun onToolbarAction(action: CandidateBar.ToolbarAction) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (action) {
            CandidateBar.ToolbarAction.MENU -> {
                if (zhComposing) zhCommitTop()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                showNextPredictions = false; showZhPredictions = false
                updateCandidates()
                setOverlay(if (overlay in setOf(Overlay.TOOLS, Overlay.CLIPBOARD, Overlay.VOICE)) Overlay.NONE else Overlay.TOOLS)
            }
            CandidateBar.ToolbarAction.HANDWRITING_TOGGLE -> toggleHandwriting()
            CandidateBar.ToolbarAction.CLIPBOARD -> {
                if (zhComposing) zhCommitTop()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                showNextPredictions = false; showZhPredictions = false
                updateCandidates()
                setOverlay(if (overlay == Overlay.CLIPBOARD) Overlay.NONE else Overlay.CLIPBOARD)
            }
            CandidateBar.ToolbarAction.PASTE_FRESH -> {
                val clip = clipboardStore.freshClip() ?: return
                clipboardStore.dismissFresh()
                currentInputConnection?.commitText(clip, 1)
                syncToolbarState()
            }
            CandidateBar.ToolbarAction.DISMISS_FRESH -> {
                clipboardStore.dismissFresh()
                syncToolbarState()
            }
            CandidateBar.ToolbarAction.VOICE -> {
                if (zhComposing) zhCommitTop()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                showNextPredictions = false; showZhPredictions = false
                updateCandidates()
                setOverlay(if (overlay == Overlay.VOICE) Overlay.NONE else Overlay.VOICE)
            }
            CandidateBar.ToolbarAction.HIDE -> {
                setOverlay(Overlay.NONE)
                requestHideSelf(0)
            }
        }
    }

    private fun toggleHandwriting() {
        if (zhComposing) zhCommitTop()
        if (hwComposing) hwCommitTop()
        if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
        showNextPredictions = false; showZhPredictions = false
        handwritingMode = !handwritingMode
        if (handwritingMode) {
            chinese = true
            page = Page.LETTERS
            prefs.edit().putBoolean(KEY_CHINESE, true).putBoolean(KEY_HANDWRITING, true).apply()
            hwWorker.execute { HandwritingEngine.getOrLoad(applicationContext) }
        } else {
            prefs.edit().putBoolean(KEY_HANDWRITING, false).apply()
            if (::handwriting.isInitialized) handwriting.clearCanvas(notify = false)
        }
        setOverlay(Overlay.NONE)
        refreshLayout()
        updateCandidates()
    }

    override fun onSelectInputMode(mode: ToolPanel.InputMode) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (mode == ToolPanel.InputMode.HANDWRITING) {
            if (!handwritingMode) toggleHandwriting() else setOverlay(Overlay.NONE)
            return
        }
        if (hwComposing) hwCommitTop()
        if (::handwriting.isInitialized) handwriting.clearCanvas(notify = false)
        handwritingMode = false
        prefs.edit().putBoolean(KEY_HANDWRITING, false).apply()
        val targetSchema = when (mode) {
            ToolPanel.InputMode.QUANPIN -> "rime_ice"
            ToolPanel.InputMode.T9 -> "t9"
            ToolPanel.InputMode.SHUANGPIN -> prefs.getString(KEY_LAST_DOUBLE, "double_pinyin_flypy") ?: "double_pinyin_flypy"
            ToolPanel.InputMode.SHAPE -> prefs.getString(KEY_LAST_SHAPE, "wubi86_jidian") ?: "wubi86_jidian"
            ToolPanel.InputMode.HANDWRITING -> return
        }
        chinese = true
        page = Page.LETTERS
        prefs.edit().putBoolean(KEY_CHINESE, true).putString(KEY_SCHEMA, targetSchema).apply()
        rememberSchemaPreference(targetSchema)
        RimeEngine.start(this, targetSchema)
        RimeEngine.selectSchema(targetSchema)
        setOverlay(Overlay.NONE)
        refreshLayout()
        updateCandidates()
    }

    override fun onToolTile(tile: ToolPanel.Tile) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (tile) {
            ToolPanel.Tile.CLIPBOARD -> setOverlay(Overlay.CLIPBOARD)
            ToolPanel.Tile.VOICE -> setOverlay(Overlay.VOICE)
            ToolPanel.Tile.TRADITIONAL -> {
                val next = !RimeEngine.traditional
                RimeEngine.traditional = next
                prefs.edit().putBoolean(KEY_TRADITIONAL, next).apply()
                syncToolbarState()
            }
            ToolPanel.Tile.SWITCH_IME -> {
                setOverlay(Overlay.NONE)
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            ToolPanel.Tile.SETTINGS -> {
                setOverlay(Overlay.NONE)
                requestHideSelf(0)
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ToolPanel.Tile.HIDE -> {
                setOverlay(Overlay.NONE)
                requestHideSelf(0)
            }
        }
    }

    override fun onEditAction(action: ToolPanel.EditAction) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val ic = currentInputConnection ?: return
        when (action) {
            ToolPanel.EditAction.LEFT -> onCursorMove(-1)
            ToolPanel.EditAction.RIGHT -> onCursorMove(1)
            ToolPanel.EditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            ToolPanel.EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            ToolPanel.EditAction.PASTE -> {
                if (!ic.performContextMenuAction(android.R.id.paste)) {
                    clipboardStore.items().firstOrNull()?.let { ic.commitText(it.text, 1) }
                }
            }
            ToolPanel.EditAction.DELETE -> onDeleteDown(false)
        }
    }

    override fun onClipboardCommit(text: String) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        clipboardStore.dismissFresh()
        currentInputConnection?.commitText(text, 1)
        setOverlay(Overlay.NONE)
        syncToolbarState()
    }

    override fun onClipboardChanged() = syncToolbarState()

    override fun onClipboardClose() = setOverlay(Overlay.NONE)

    override fun onVoiceCommit(text: String) {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val prevZh = trailingZhWordBeforeCursor()
        ic.commitText(text, 1)
        if (text.any(ChinesePredictor::isCjk)) {
            recordChineseCommit(prevZh, text)
            updateCandidates()
        }
    }

    override fun onVoicePartial(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) ic.finishComposingText() else ic.setComposingText(text, 1)
    }

    override fun onVoiceDelete() = onDeleteDown(false)

    override fun onVoiceClose() {
        setOverlay(Overlay.NONE)
        updateCandidates()
    }

    override fun onVoiceRequestPermission() {
        requestHideSelf(0)
        startActivity(
            Intent(this, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_REQUEST_MIC, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Captures the trailing Chinese word right before the cursor (up to 4 CJK chars) for bigram learning. */
    private fun trailingZhWordBeforeCursor(skipSuffixLen: Int = 0): String? {
        val raw = currentInputConnection?.getTextBeforeCursor(8 + skipSuffixLen, 0)?.toString() ?: return null
        val before = if (skipSuffixLen > 0 && raw.length >= skipSuffixLen) raw.dropLast(skipSuffixLen) else raw
        if (before.isEmpty()) return null
        var i = before.length
        while (i > 0 && ChinesePredictor.isCjk(before[i - 1]) && before.length - i < 4) i--
        return before.substring(i).takeIf { it.isNotEmpty() }
    }

    private fun recordChineseCommit(prevZh: String?, committed: String) {
        AiRanker.remember(committed)
        if (scene.learn) zhPredictor?.learn(prevZh, committed)
        showZhPredictions = scene.suggestions && scene.kind != FieldKind.PASSWORD &&
            committed.isNotEmpty() && ChinesePredictor.isCjk(committed.last())
    }

    // ------------------------------------------------------------------ keyboard callbacks

    override fun onKeyDownFeedback(key: Key) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            val fx = when (key.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am.playSoundEffect(fx, -1f)
        }
    }

    override fun onText(key: Key, text: String) {
        lastEditAt = SystemClock.uptimeMillis()
        lastAutoCorrect = null
        val ic = currentInputConnection ?: return
        if (zhActive && page == Page.LETTERS && text.length == 1) {
            val c = text[0]
            if (c in 'a'..'z' || (zhComposing && c in 'A'..'Z')) {
                zhKey(c.lowercaseChar().code)
                if (shift == ShiftState.ONCE) setShift(ShiftState.OFF)
                return
            }
        }
        if (zhComposing) zhCommitTop()
        val letterLike = text.length == 1 && (text[0].isLetter() || (text == "'" && composing.isNotEmpty()))
        val composes = page == Page.LETTERS && letterLike && usesComposing()
        if (composes) {
            composing.append(text)
            showNextPredictions = false
            showZhPredictions = false
            ic.setComposingText(composing, 1)
            if (shift == ShiftState.ONCE) setShift(ShiftState.OFF)
        } else {
            if (composing.isNotEmpty()) {
                val punct = text.length == 1 && text[0] in ".,?!;:"
                commitComposing(useAutoCorrect = punct)
            }
            ic.commitText(text, 1)
            showNextPredictions = false
            showZhPredictions = false
            if (shift == ShiftState.ONCE && page == Page.LETTERS) setShift(ShiftState.OFF)
            if (text.length == 1 && text[0] in ".?!。？！") updateAutoShift()
        }
        updateCandidates()
    }

    private fun usesComposing(): Boolean {
        if (chinese) return !RimeEngine.isReady
        if (!scene.suggestions) return false
        return when (scene.kind) {
            FieldKind.TEXT, FieldKind.PERSON_NAME, FieldKind.URL -> true
            FieldKind.EMAIL -> {
                val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
                '@' !in before.substringAfterLast(' ')
            }
            else -> false
        }
    }

    override fun onFunction(key: Key) {
        val ic = currentInputConnection
        when (key.type) {
            KeyType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                setShift(when {
                    shift == ShiftState.LOCK -> ShiftState.OFF
                    now - lastShiftTap < 350 -> ShiftState.LOCK
                    shift == ShiftState.ONCE -> ShiftState.OFF
                    else -> ShiftState.ONCE
                })
                lastShiftTap = now
                if (showNextPredictions && composing.isEmpty()) updateCandidates()
            }
            KeyType.SPACE -> onSpace()
            KeyType.ENTER -> {
                lastAutoCorrect = null
                showNextPredictions = false
                showZhPredictions = false
                if (hwComposing) { hwCommitTop(); return }
                if (zhComposing) { zhCommitRaw(); return }
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                if (scene.action != EditorInfo.IME_ACTION_NONE) ic?.performEditorAction(scene.action)
                else sendKeyChar('\n')
                updateAutoShift()
                updateCandidates()
            }
            KeyType.TO_SYMBOLS -> { if (hwComposing) hwCommitTop(); page = Page.SYMBOLS; refreshLayout() }
            KeyType.TO_SYMBOLS2 -> { page = Page.SYMBOLS2; refreshLayout() }
            KeyType.TO_LETTERS -> { page = Page.LETTERS; refreshLayout(); updateAutoShift() }
            KeyType.LANG -> {
                if (hwComposing) hwCommitTop()
                if (zhComposing) zhCommitRaw()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                showNextPredictions = false
                showZhPredictions = false
                chinese = !chinese
                if (!chinese) handwritingMode = false
                prefs.edit().putBoolean(KEY_CHINESE, chinese).apply()
                if (chinese) RimeEngine.start(this, prefs.getString(KEY_SCHEMA, null))
                refreshLayout(); updateAutoShift(); updateCandidates()
            }
            KeyType.EMOJI -> {
                if (hwComposing) hwCommitTop()
                if (zhComposing) zhCommitTop()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                showNextPredictions = false
                showZhPredictions = false
                setOverlay(Overlay.EMOJI)
            }
            else -> Unit
        }
    }

    override fun onLongPressFunction(key: Key) {
        when (key.type) {
            KeyType.LANG, KeyType.EMOJI ->
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            else -> Unit
        }
    }

    private fun onSpace() {
        lastEditAt = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (hwComposing) {
            hwCommitTop()
            return
        }
        if (zhComposing) {
            if (aiSuggestion != null) commitAi() else zhKey(Rime.XK_SPACE)
            return
        }
        showZhPredictions = false
        if (composing.isNotEmpty()) {
            if (chinese) {
                commitComposing(useAutoCorrect = false)
                ic.commitText(" ", 1)
            } else {
                val typed = composing.toString()
                val committed = commitComposing(useAutoCorrect = true)
                ic.commitText(" ", 1)
                if (committed != typed) lastAutoCorrect = typed to committed
                showNextPredictions = scene.suggestions && scene.kind in setOf(FieldKind.TEXT, FieldKind.URL)
            }
        } else {
            lastAutoCorrect = null
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (doubleSpacePeriod && !chinese && scene.kind == FieldKind.TEXT && now - lastSpaceAt < 450 && before.length == 2 &&
                before[1] == ' ' && before[0].isLetterOrDigit()
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0
                showNextPredictions = false
                updateAutoShift(); updateCandidates()
                return
            }
            ic.commitText(" ", 1)
            showNextPredictions = !chinese && scene.suggestions && scene.kind in setOf(FieldKind.TEXT, FieldKind.URL)
        }
        lastSpaceAt = now
        if (page == Page.SYMBOLS || page == Page.SYMBOLS2) { page = Page.LETTERS; refreshLayout() }
        updateAutoShift()
        updateCandidates()
    }

    override fun onDeleteDown(repeat: Boolean) {
        lastEditAt = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        if (zhComposing) { zhKey(Rime.XK_BACKSPACE); return }
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
            updateCandidates()
            if (composing.isEmpty()) updateAutoShift()
            return
        }
        showNextPredictions = false
        showZhPredictions = false
        val undo = lastAutoCorrect
        lastAutoCorrect = null
        if (undo != null && !repeat) {
            val (typed, corrected) = undo
            val before = ic.getTextBeforeCursor(corrected.length + 1, 0)?.toString()
            if (before == "$corrected ") {
                ic.deleteSurroundingText(corrected.length + 1, 0)
                composing.append(typed)
                rejected = typed
                ic.setComposingText(composing, 1)
                updateCandidates()
                return
            }
        }
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        updateAutoShift()
        updateCandidates()
    }

    override fun onCursorMove(steps: Int) {
        if (zhComposing) zhCommitRaw()
        if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
        showNextPredictions = false
        showZhPredictions = false
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Commits the composing word; returns what was actually committed. */
    private fun commitComposing(useAutoCorrect: Boolean): String {
        val ic = currentInputConnection
        val prev = if (!chinese) prevEnglishWord() else null
        val typed = composing.toString()
        var out = typed
        if (!chinese && useAutoCorrect && autoCorrect && scene.autoCorrect) {
            if (typed != rejected) result?.takeIf { it.typed == typed }?.autoCorrect?.let { out = it }
        }
        ic?.commitText(out, 1)
        if (!chinese && scene.learn) english?.learn(out, prevWord = prev)
        composing.clear(); result = null
        rejected = null
        return out
    }

    // ------------------------------------------------------------------ candidates

    private fun updateCandidates() {
        if (!::candidates.isInitialized) return
        if (::preeditBar.isInitialized) {
            preeditBar.text = when {
                zhComposing -> zhDisplay()
                chinese && composing.isNotEmpty() -> composing.toString()
                else -> ""
            }
        }
        val labels = ArrayList<String>()
        val commits = ArrayList<String>()
        var highlight = -1
        var keepToolbarEdges = false
        when {
            hwComposing -> {
                for (c in hwCandidates) { labels += c; commits += c }
                highlight = 0
            }
            zhComposing -> {
                aiSuggestion?.let { labels += it; commits += it }
                zh.candidates.forEach { if (it.text != aiSuggestion) { labels += it.text; commits += it.text } }
                highlight = 0
            }
            composing.isNotEmpty() && chinese -> {
                labels += composing.toString(); commits += composing.toString()
                labels += if (RimeEngine.state == RimeEngine.State.FAILED) "中文引擎加载失败" else "正在准备中文词库…"
                commits += composing.toString()
                highlight = 0
            }
            composing.isNotEmpty() -> {
                val typed = composing.toString()
                val prev = prevEnglishWord()
                val r = english?.suggest(typed, prevWord = prev)
                result = r
                val auto = if (autoCorrect && scene.autoCorrect && typed != rejected) r?.autoCorrect else null
                if (auto != null) {
                    labels += "“$typed”"; commits += typed
                    labels += auto; commits += auto; highlight = 1
                } else {
                    labels += typed; commits += typed; highlight = 0
                }
                if (scene.kind == FieldKind.URL) {
                    val low = typed.lowercase()
                    val rawBefore = currentInputConnection?.getTextBeforeCursor(32 + typed.length, 0)?.toString().orEmpty()
                    val prefixBefore = if (rawBefore.endsWith(typed)) rawBefore.dropLast(typed.length) else rawBefore
                    if (prefixBefore.isEmpty()) {
                        if ("https".startsWith(low)) { labels += "https://"; commits += "https://" }
                        if ("www".startsWith(low)) { labels += "www."; commits += "www." }
                    }
                    r?.suggestions?.forEach { s ->
                        if (s.text != typed && s.text !in labels) { labels += s.text; commits += s.text }
                    }
                    if (' ' !in prefixBefore && '.' !in prefixBefore && low.length >= 2) {
                        val dom = "$low.com"
                        if (dom !in labels) {
                            val ins = minOf(2, labels.size)
                            labels.add(ins, dom); commits.add(ins, dom)
                        }
                    }
                } else {
                    r?.suggestions?.forEach { s ->
                        if (s.text != typed && s.text != auto) { labels += s.text; commits += s.text }
                    }
                }
            }
            scene.kind == FieldKind.EMAIL && !chinese -> {
                emailSuggestions(labels, commits)
                keepToolbarEdges = labels.isNotEmpty()
            }
            scene.kind == FieldKind.URL && !chinese && urlSuggestions(labels, commits) -> {
                keepToolbarEdges = labels.isNotEmpty()
            }
            chinese && showZhPredictions && scene.suggestions && scene.kind != FieldKind.PASSWORD && overlay == Overlay.NONE -> {
                val before = currentInputConnection?.getTextBeforeCursor(8, 0)?.toString().orEmpty()
                var nexts = zhPredictor?.predict(before, max = 8).orEmpty()
                if (RimeEngine.traditional && nexts.isNotEmpty()) {
                    nexts = nexts.map { HandwritingEngine.toTraditional(this, it) }.distinct()
                }
                for (w in nexts) { labels += w; commits += w }
                keepToolbarEdges = labels.isNotEmpty()
            }
            !chinese && showNextPredictions && scene.suggestions && scene.kind in setOf(FieldKind.TEXT, FieldKind.URL) && overlay == Overlay.NONE -> {
                val prev = prevEnglishWord()
                val nexts = english?.predictNext(prev, capitalizeFirst = shift != ShiftState.OFF, max = 6).orEmpty()
                for (w in nexts) { labels += w; commits += w }
                keepToolbarEdges = labels.isNotEmpty()
            }
        }
        if (!scene.suggestions && !chinese) { labels.clear(); commits.clear(); highlight = -1; keepToolbarEdges = false }
        if (labels.isEmpty() && chinese && RimeEngine.state == RimeEngine.State.DEPLOYING) {
            labels += "正在准备中文词库…"; commits += ""
        }
        shownCandidates = labels
        candidateCommits = commits
        syncToolbarState()
        candidates.expandable = (zhComposing || hwComposing) && labels.size > 3
        candidates.setItems(labels, highlight, keepToolbarEdges = keepToolbarEdges)
        if (!zhComposing && !hwComposing && overlay == Overlay.CANDIDATES) setOverlay(Overlay.NONE)
    }

    private fun emailSuggestions(labels: MutableList<String>, commits: MutableList<String>) {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        val token = before.substringAfterLast(' ')
        val at = token.lastIndexOf('@')
        if (at < 0) return
        val typedDomain = token.substring(at + 1).lowercase()
        for (d in EMAIL_DOMAINS) if (d.startsWith(typedDomain) && d != typedDomain) {
            labels += "@$d"; commits += d.substring(typedDomain.length)
        }
    }

    /** Populates domain suffix chips ONLY immediately after `.` or `:`, leaving the toolbar visible when empty. */
    private fun urlSuggestions(labels: MutableList<String>, commits: MutableList<String>): Boolean {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return false
        if (before.isEmpty() || ' ' in before) return false
        if (before == "https:" || before == "http:") {
            labels += "//"; commits += "//"
            return true
        }
        val hostPart = before.substringAfter("://").substringBefore('/')
        if (before.endsWith(".") && hostPart.length >= 2) {
            for (tld in listOf("com", "cn", "net", "org", "com.cn", "io")) {
                labels += ".$tld"; commits += tld
            }
            return true
        }
        return false
    }

    private fun pickCandidate(index: Int) {
        lastEditAt = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        if (hwComposing) {
            val picked = candidateCommits.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
            hwCommit(picked)
            return
        }
        if (zhComposing) {
            val ai = aiSuggestion
            if (ai != null && index == 0) { commitAi(); return }
            val text = candidateCommits.getOrNull(index)
            val rimeIndex = zh.candidates.indexOfFirst { it.text == text }.takeIf { it >= 0 } ?: index
            if (ai != null) AiRanker.feedback(pickedAi = false, pickedRime = rimeIndex == 0)
            RimeEngine.select(rimeIndex)
            zhSync()
            return
        }
        val text = candidateCommits.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        lastAutoCorrect = null
        if (composing.isNotEmpty()) {
            val prev = if (!chinese) prevEnglishWord() else null
            ic.commitText(text, 1)
            if (!chinese && scene.learn) english?.learn(text, prevWord = prev)
            composing.clear(); result = null
            if (scene.spaceAfterPick && !chinese) {
                ic.commitText(" ", 1)
                showNextPredictions = scene.suggestions && scene.kind == FieldKind.TEXT
            } else if (!chinese && scene.kind == FieldKind.URL && !text.endsWith(".") && !text.endsWith("/")) {
                // Allow English search phrase continuation if user hits space or let URL stay clean
                showNextPredictions = false
            }
        } else if (chinese && showZhPredictions) {
            val prevZh = trailingZhWordBeforeCursor()
            ic.commitText(text, 1)
            recordChineseCommit(prevZh, text)
        } else if (!chinese && showNextPredictions && scene.kind in setOf(FieldKind.TEXT, FieldKind.URL)) {
            val prev = prevEnglishWord()
            val suffix = if (scene.kind == FieldKind.TEXT) " " else " "
            ic.commitText(text + suffix, 1)
            if (scene.learn) english?.learn(text, prevWord = prev)
            if (shift == ShiftState.ONCE) setShift(ShiftState.OFF)
            showNextPredictions = true
        } else {
            ic.commitText(text, 1)
        }
        updateAutoShift()
        updateCandidates()
    }

    // ------------------------------------------------------------------ Chinese (librime)

    private var zh = EMPTY_ZH
    private val zhComposing: Boolean get() = zh.composing
    private val zhActive: Boolean get() = chinese && RimeEngine.isReady

    private val rimeListener: () -> Unit = {
        if (::keyboard.isInitialized && chinese) { refreshLayout(); updateCandidates() }
    }

    private fun zhKey(code: Int) {
        lastEditAt = SystemClock.uptimeMillis()
        RimeEngine.processKey(code)
        zhSync()
    }

    private fun zhSync() {
        lastEditAt = SystemClock.uptimeMillis()
        val ic = currentInputConnection
        val oldShownLen = if (zhComposing) zhDisplay().length else 0
        val commit = RimeEngine.takeCommit()
        zh = RimeEngine.snapshot()
        if (commit != null) {
            val prevZh = trailingZhWordBeforeCursor(skipSuffixLen = oldShownLen)
            ic?.commitText(commit, 1)
            recordChineseCommit(prevZh, commit)
        }
        if (zh.input != aiInput) aiSuggestion = null
        if (zh.composing) {
            showZhPredictions = false
            ic?.setComposingText(zhDisplay(), 1)
        } else if (commit == null) {
            ic?.commitText("", 1)
        }
        updateCandidates()
        updateT9Side()
        scheduleAi()
    }

    // ------------------------------------------------------------------ AI re-ranking

    private var aiSuggestion: String? = null
    private var aiInput: String = ""
    private val aiWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai").apply { priority = Thread.MIN_PRIORITY }
    }
    private val aiHandler = Handler(Looper.getMainLooper())
    private var aiPending: Runnable? = null
    private val aiGeneration = java.util.concurrent.atomic.AtomicLong()

    private fun aiEnabled(): Boolean = prefs.getBoolean(KEY_AI, true) && AiRanker.hasModel(this)

    private fun scheduleAi() {
        aiPending?.let { aiHandler.removeCallbacks(it) }
        val gen = aiGeneration.incrementAndGet()
        AiRanker.abort()
        if (!zhComposing || !aiEnabled()) return
        val schema = RimeEngine.currentSchema() ?: return
        if (!AiRanker.supports(schema)) return
        val shown = zhDisplay()
        if (shown.any { it in '㐀'..'鿿' }) return
        val syllables = AiRanker.syllableCount(shown)
        if (syllables < 3) return
        val input = zh.input
        val before = currentInputConnection?.getTextBeforeCursor(48 + shown.length, 0)?.toString().orEmpty()
        val context = before.dropLast(shown.length.coerceAtMost(before.length))
        val job = Runnable {
            aiWorker.execute {
                try {
                    if (gen != aiGeneration.get() || !AiRanker.ensureLoaded(applicationContext)) return@execute
                    val s = AiRanker.suggest(context, schema, input, syllables) { gen != aiGeneration.get() } ?: return@execute
                    aiHandler.post {
                        if (zh.input == input && zhComposing) {
                            aiSuggestion = s.text; aiInput = input
                            updateCandidates()
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("ImeService", "AI suggestion failed", t)
                }
            }
        }
        aiPending = job
        aiHandler.postDelayed(job, 250)
    }

    private fun commitAi() {
        val text = aiSuggestion ?: return
        lastEditAt = SystemClock.uptimeMillis()
        val oldShownLen = if (zhComposing) zhDisplay().length else 0
        val prevZh = trailingZhWordBeforeCursor(skipSuffixLen = oldShownLen)
        RimeEngine.clear()
        zh = EMPTY_ZH
        aiSuggestion = null
        currentInputConnection?.commitText(text, 1)
        AiRanker.feedback(pickedAi = true, pickedRime = false)
        recordChineseCommit(prevZh, text)
        setOverlay(Overlay.NONE)
        updateCandidates()
        updateT9Side()
    }

    private fun zhDisplay(): String {
        if (isT9()) {
            val (head, tail) = T9.split(zh.input)
            val spelled = zh.candidates.firstOrNull()?.comment?.takeIf { it.isNotBlank() }
            if (spelled != null && tail.isNotEmpty()) return spelled
            if (tail.isEmpty()) return head.replace('\'', ' ')
        }
        return zh.preedit.ifEmpty { zh.input }
    }

    // ------------------------------------------------------------------ stack, 九宫格 & 手写

    private fun isHandwritingVisible(): Boolean = chinese && page == Page.LETTERS && handwritingMode
    private fun isT9(): Boolean = chinese && page == Page.LETTERS && RimeEngine.currentSchema() == "t9" && !handwritingMode

    private fun syncStack() {
        if (!::keyboard.isInitialized) return
        val hwOn = overlay == Overlay.NONE && isHandwritingVisible()
        val t9On = overlay == Overlay.NONE && !hwOn && isT9()
        emoji.visibility = if (overlay == Overlay.EMOJI) View.VISIBLE else View.GONE
        candidatePanel.visibility = if (overlay == Overlay.CANDIDATES) View.VISIBLE else View.GONE
        toolPanel.visibility = if (overlay == Overlay.TOOLS) View.VISIBLE else View.GONE
        clipboardPanel.visibility = if (overlay == Overlay.CLIPBOARD) View.VISIBLE else View.GONE
        voicePanel.visibility = if (overlay == Overlay.VOICE) View.VISIBLE else View.GONE
        handwriting.visibility = if (hwOn) View.VISIBLE else View.GONE
        t9.visibility = if (t9On) View.VISIBLE else View.GONE
        // The QWERTY view stays laid out (INVISIBLE) so the stack keeps its height.
        keyboard.visibility = if (overlay == Overlay.NONE && !t9On && !hwOn) View.VISIBLE else View.INVISIBLE
    }

    override fun onHandwritingStrokes(strokes: List<List<HandwritingEngine.Point>>) {
        val gen = hwGen.incrementAndGet()
        hwWorker.execute {
            val engine = HandwritingEngine.getOrLoad(applicationContext) ?: return@execute
            val rawCands = engine.recognize(strokes, topK = 36, preferChinese = true)
            val cands = if (RimeEngine.traditional) {
                rawCands.map { HandwritingEngine.toTraditional(applicationContext, it) }.distinct()
            } else rawCands
            uiHandler.post {
                if (gen != hwGen.get() || !handwritingMode) return@post
                lastEditAt = SystemClock.uptimeMillis()
                hwCandidates = cands
                handwriting.hasPendingComposition = cands.isNotEmpty()
                val top = cands.firstOrNull()
                if (top != null) currentInputConnection?.setComposingText(top, 1)
                updateCandidates()
            }
        }
    }

    override fun onHandwritingAutoCommitPrevious() {
        val top = hwCandidates.firstOrNull() ?: return
        lastEditAt = SystemClock.uptimeMillis()
        val prevZh = trailingZhWordBeforeCursor(skipSuffixLen = top.length)
        hwGen.incrementAndGet()
        hwCandidates = emptyList()
        handwriting.hasPendingComposition = false
        currentInputConnection?.commitText(top, 1)
        recordChineseCommit(prevZh, top)
        updateCandidates()
    }

    override fun onHandwritingClear() {
        lastEditAt = SystemClock.uptimeMillis()
        hwGen.incrementAndGet()
        hwCandidates = emptyList()
        showZhPredictions = false
        handwriting.hasPendingComposition = false
        currentInputConnection?.commitText("", 1)
        HandwritingEngine.getOrLoad(applicationContext)?.clearCache()
        updateCandidates()
    }

    override fun onHandwritingPunct(punct: String) {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (hwComposing) hwCommitTop()
        showZhPredictions = false
        currentInputConnection?.commitText(punct, 1)
        updateCandidates()
    }

    override fun onHandwritingExitToKeyboard() {
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (hwComposing) hwCommitTop()
        handwritingMode = false
        prefs.edit().putBoolean(KEY_HANDWRITING, false).apply()
        handwriting.clearCanvas(notify = false)
        refreshLayout()
        updateCandidates()
    }

    private fun hwCommitTop() {
        val top = hwCandidates.firstOrNull() ?: return
        hwCommit(top)
    }

    private fun hwCommit(text: String) {
        lastEditAt = SystemClock.uptimeMillis()
        val oldTopLen = hwCandidates.firstOrNull()?.length ?: 0
        val prevZh = trailingZhWordBeforeCursor(skipSuffixLen = oldTopLen)
        hwGen.incrementAndGet()
        hwCandidates = emptyList()
        if (::handwriting.isInitialized) {
            handwriting.clearCanvas(notify = false)
            handwriting.hasPendingComposition = false
        }
        currentInputConnection?.commitText(text, 1)
        recordChineseCommit(prevZh, text)
        setOverlay(Overlay.NONE)
        updateCandidates()
    }

    private val T9_PUNCT = listOf("，", "。", "？", "！", "、", "：", "；", "…", "～", "“", "”", "（", "）")
    private var t9SideIsPinyin = false

    private fun updateT9Side() {
        if (!::t9.isInitialized || !isT9()) return
        val tail = T9.split(zh.input).second
        t9SideIsPinyin = zhComposing && tail.isNotEmpty()
        t9.sideItems = if (t9SideIsPinyin) T9.options(tail) else T9_PUNCT
        t9.sideHighlightsFirst = false
    }

    override fun onT9Digit(digit: Char) { if (RimeEngine.isReady) zhKey(digit.code) }

    override fun onT9Separator() { if (zhComposing) zhKey('\''.code) }

    override fun onT9Clear() {
        if (!zhComposing) return
        RimeEngine.clear()
        zhSync()
    }

    override fun onT9Side(index: Int) {
        val item = t9.sideItems.getOrNull(index) ?: return
        if (t9SideIsPinyin && zhComposing) {
            val input = T9.applyPick(zh.input, item)
            RimeEngine.clear()
            input.forEach { RimeEngine.processKey(it.code) }
            zhSync()
        } else {
            if (zhComposing) zhCommitTop()
            currentInputConnection?.commitText(item, 1)
        }
    }

    private fun pickFromPanel(index: Int) {
        if (hwComposing) {
            val picked = hwCandidates.getOrNull(index)
            if (picked != null) hwCommit(picked) else setOverlay(Overlay.NONE)
            return
        }
        if (!zhComposing) { setOverlay(Overlay.NONE); return }
        RimeEngine.select(index)
        zhSync()
        setOverlay(Overlay.NONE)
    }

    private fun zhCommitTop() {
        if (!zhComposing) return
        if (zh.candidates.isEmpty()) { zhCommitRaw(); return }
        if (aiSuggestion != null) commitAi() else zhKey(Rime.XK_SPACE)
    }

    private fun zhCommitRaw() {
        if (!zhComposing) return
        val raw = if (isT9()) zhDisplay().replace(" ", "") else zh.input
        RimeEngine.clear()
        zh = EMPTY_ZH
        currentInputConnection?.commitText(raw, 1)
        updateCandidates()
    }

    // ------------------------------------------------------------------ emoji

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onEmojiDelete() = onDeleteDown(false)

    override fun onEmojiClose() {
        setOverlay(Overlay.NONE)
        updateAutoShift()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            aiWorker.execute { AiRanker.unload() }
            if (!handwritingMode) hwWorker.execute { HandwritingEngine.unload() }
            if (overlay != Overlay.VOICE) OfflineAsrEngine.unload()
        }
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_CHINESE = "chinese"
        const val KEY_HANDWRITING = "handwriting"
        const val KEY_SCHEMA = "schema"
        const val KEY_LAST_DOUBLE = "last_double_schema"
        const val KEY_LAST_SHAPE = "last_shape_schema"
        const val KEY_TRADITIONAL = "traditional"
        const val KEY_AI = "ai_rerank"
        const val KEY_AI_ALPHA = "ai_alpha"
        private val EMPTY_ZH = RimeEngine.Snapshot("", "", emptyList())
        val EMAIL_DOMAINS = listOf(
            "qq.com", "163.com", "gmail.com", "126.com", "outlook.com", "icloud.com", "foxmail.com",
            "hotmail.com", "sina.com", "yahoo.com", "aliyun.com", "139.com", "sohu.com", "proton.me",
        )
    }
}
