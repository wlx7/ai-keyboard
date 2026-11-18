package app.aikb.ime

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
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
import app.aikb.ime.engine.EnglishDictionary
import app.aikb.ime.engine.EnglishEngine
import app.aikb.ime.engine.EnglishResult
import app.aikb.ime.engine.AiRanker
import app.aikb.ime.engine.Rime
import app.aikb.ime.engine.RimeEngine
import app.aikb.ime.engine.UserHistory
import app.aikb.ime.keyboard.CandidateBar
import app.aikb.ime.keyboard.CandidatePanel
import app.aikb.ime.keyboard.EmojiPanel
import app.aikb.ime.keyboard.KbTheme
import app.aikb.ime.keyboard.Key
import app.aikb.ime.keyboard.KeyType
import app.aikb.ime.keyboard.KeyboardView
import app.aikb.ime.keyboard.Layout
import app.aikb.ime.keyboard.Layouts
import app.aikb.ime.keyboard.Page
import app.aikb.ime.keyboard.PreviewOverlay
import app.aikb.ime.keyboard.ShiftState
import app.aikb.ime.keyboard.T9View
import app.aikb.ime.engine.T9

class ImeService : InputMethodService(), KeyboardView.Listener, EmojiPanel.Listener, T9View.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var history: UserHistory
    private var english: EnglishEngine? = null

    private lateinit var root: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var candidates: CandidateBar
    private lateinit var emoji: EmojiPanel
    private lateinit var candidatePanel: CandidatePanel
    private lateinit var t9: T9View
    private lateinit var previewOverlay: PreviewOverlay

    private var scene = Scene.of(null)
    private var page = Page.LETTERS
    private var chinese = false
    private var shift = ShiftState.OFF
    private var lastShiftTap = 0L

    /** Word being typed (English) or pinyin being typed (Chinese), shown as composing text. */
    private val composing = StringBuilder()
    private var result: EnglishResult? = null
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

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        history = UserHistory(filesDir.resolve("en_user.tsv"))
        english = runCatching { EnglishEngine(EnglishDictionary.load(this), history) }.getOrNull()
        RimeEngine.addStateListener(rimeListener)
        RimeEngine.traditional = prefs.getBoolean(KEY_TRADITIONAL, false)
        AiRanker.alpha = prefs.getFloat(KEY_AI_ALPHA, AiRanker.DEFAULT_ALPHA)
        RimeEngine.start(this, prefs.getString(KEY_SCHEMA, null))
    }

    override fun onDestroy() {
        RimeEngine.removeStateListener(rimeListener)
        aiWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val theme = KbTheme.of(this)
        previewOverlay = PreviewOverlay(this)
        keyboard = KeyboardView(this, this).also { it.previewOverlay = previewOverlay }
        candidates = CandidateBar(this) { pickCandidate(it) }
        emoji = EmojiPanel(this, this).apply { visibility = View.GONE }
        candidatePanel = CandidatePanel(this) { pickFromPanel(it) }.apply { visibility = View.GONE }
        t9 = T9View(this, this).apply { visibility = View.GONE }
        candidates.onExpandToggle = { setPanelExpanded(!candidates.expanded) }

        val stack = FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(t9, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(candidatePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(candidates, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(previewOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnApplyWindowInsetsListener { v, insets ->
                // On Android 15 the IME nav strip (back / switcher buttons) is reported as a tappable
                // element taller than the nav bar itself; keep keys clear of both.
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
        keyboard.theme = t; candidates.theme = t; emoji.theme = t; previewOverlay.theme = t; candidatePanel.theme = t; t9.theme = t
        root.setBackgroundColor(t.background)
        @Suppress("DEPRECATION")
        window.window?.navigationBarColor = t.background
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadSettings()
        applyTheme(KbTheme.of(this))
        scene = Scene.of(info)
        chinese = prefs.getBoolean(KEY_CHINESE, true) && !scene.forceEnglish
        page = scene.initialPage
        shift = ShiftState.OFF
        composing.clear(); result = null; lastAutoCorrect = null
        RimeEngine.traditional = prefs.getBoolean(KEY_TRADITIONAL, false)
        RimeEngine.start(this, prefs.getString(KEY_SCHEMA, null)) // no-op when ready; applies a new scheme choice
        RimeEngine.clear(); zh = EMPTY_ZH
        setPanelExpanded(false)
        showEmoji(false)
        refreshLayout()
        updateAutoShift()
        updateCandidates()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (composing.isNotEmpty()) { currentInputConnection?.finishComposingText(); composing.clear() }
        if (zhComposing) { RimeEngine.clear(); zh = EMPTY_ZH; currentInputConnection?.finishComposingText() }
        if (::keyboard.isInitialized) keyboard.cancelAll()
        history.save()
        prefs.edit().putFloat(KEY_AI_ALPHA, AiRanker.alpha).apply()
        aiSuggestion = null
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Cursor moved away from the word being composed (tap elsewhere, cursor drag): drop composition.
        // An update with no composing region right after our own edit is usually the echo of an
        // earlier commit racing fast typing; dropping the word then would eat keystrokes.
        val stale = candidatesEnd < 0 && SystemClock.uptimeMillis() - lastEditAt < 300
        if (composing.isNotEmpty() && !stale && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            composing.clear(); result = null
            updateCandidates()
        }
        if (zhComposing && !stale && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            RimeEngine.clear(); zh = EMPTY_ZH
            currentInputConnection?.finishComposingText()
            updateCandidates()
        }
        if (composing.isEmpty() && !zhComposing) updateAutoShift()
    }

    private fun loadSettings() {
        autoCorrect = prefs.getBoolean("auto_correct", true)
        autoCaps = prefs.getBoolean("auto_caps", true)
        doubleSpacePeriod = prefs.getBoolean("double_space", true)
        haptics = prefs.getBoolean("haptics", true)
        sound = prefs.getBoolean("sound", false)
    }

    // ------------------------------------------------------------------ layout

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
            // Lowercase letters go to librime; while composing, shifted letters do too.
            if (c in 'a'..'z' || (zhComposing && c in 'A'..'Z')) {
                zhKey(c.lowercaseChar().code)
                if (shift == ShiftState.ONCE) setShift(ShiftState.OFF)
                return
            }
        }
        // Anything else (punctuation, digits, capitals) ends the Chinese composition with the top candidate.
        if (zhComposing) zhCommitTop()
        val letterLike = text.length == 1 && (text[0].isLetter() || (text == "'" && composing.isNotEmpty()))
        val composes = page == Page.LETTERS && letterLike && usesComposing()
        if (composes) {
            composing.append(text)
            ic.setComposingText(composing, 1)
            if (shift == ShiftState.ONCE) setShift(ShiftState.OFF)
        } else {
            if (composing.isNotEmpty()) {
                // punctuation ends a word; auto-correct like space would (English prose only)
                val punct = text.length == 1 && text[0] in ".,?!;:"
                commitComposing(useAutoCorrect = punct)
            }
            ic.commitText(text, 1)
            if (shift == ShiftState.ONCE && page == Page.LETTERS) setShift(ShiftState.OFF)
            if (text.length == 1 && text[0] in ".?!。？！") updateAutoShift()
        }
        updateCandidates()
    }

    /** Composition (marked text) is used for prose and Chinese; addresses and passwords type straight through. */
    private fun usesComposing(): Boolean =
        if (chinese) !RimeEngine.isReady // raw-letter fallback while the Chinese engine deploys
        else scene.suggestions && (scene.kind == FieldKind.TEXT || scene.kind == FieldKind.PERSON_NAME)

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
            }
            KeyType.SPACE -> onSpace()
            KeyType.ENTER -> {
                lastAutoCorrect = null
                if (zhComposing) { zhCommitRaw(); return } // like iOS: return confirms the letters as typed
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                if (scene.action != EditorInfo.IME_ACTION_NONE) ic?.performEditorAction(scene.action)
                else sendKeyChar('\n')
                updateAutoShift()
                updateCandidates()
            }
            KeyType.TO_SYMBOLS -> { page = Page.SYMBOLS; refreshLayout() }
            KeyType.TO_SYMBOLS2 -> { page = Page.SYMBOLS2; refreshLayout() }
            KeyType.TO_LETTERS -> { page = Page.LETTERS; refreshLayout(); updateAutoShift() }
            KeyType.LANG -> {
                if (zhComposing) zhCommitRaw()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                chinese = !chinese
                prefs.edit().putBoolean(KEY_CHINESE, chinese).apply()
                if (chinese) RimeEngine.start(this, prefs.getString(KEY_SCHEMA, null))
                refreshLayout(); updateAutoShift(); updateCandidates()
            }
            KeyType.EMOJI -> {
                if (zhComposing) zhCommitTop()
                if (composing.isNotEmpty()) commitComposing(useAutoCorrect = false)
                showEmoji(true)
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
        if (zhComposing) {
            if (aiSuggestion != null) commitAi() else zhKey(Rime.XK_SPACE) // picks the highlighted candidate
            return
        }
        if (composing.isNotEmpty()) {
            if (chinese) {
                // M1: no Chinese engine yet, commit the raw letters. librime lands in M2.
                commitComposing(useAutoCorrect = false)
                ic.commitText(" ", 1)
            } else {
                val typed = composing.toString()
                val committed = commitComposing(useAutoCorrect = true)
                ic.commitText(" ", 1)
                if (committed != typed) lastAutoCorrect = typed to committed
            }
        } else {
            lastAutoCorrect = null
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (doubleSpacePeriod && !chinese && now - lastSpaceAt < 450 && before.length == 2 &&
                before[1] == ' ' && before[0].isLetterOrDigit()
            ) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0
                updateAutoShift(); updateCandidates()
                return
            }
            ic.commitText(" ", 1)
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
        val undo = lastAutoCorrect
        lastAutoCorrect = null
        if (undo != null && !repeat) {
            // iOS behaviour: backspace right after an auto-correction restores what was typed.
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
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Commits the composing word; returns what was actually committed. */
    private fun commitComposing(useAutoCorrect: Boolean): String {
        val ic = currentInputConnection
        val typed = composing.toString()
        var out = typed
        if (!chinese && useAutoCorrect && autoCorrect && scene.autoCorrect) {
            if (typed != rejected) result?.takeIf { it.typed == typed }?.autoCorrect?.let { out = it }
        }
        ic?.commitText(out, 1)
        if (!chinese && scene.learn) english?.learn(out)
        composing.clear(); result = null
        rejected = null
        return out
    }

    // ------------------------------------------------------------------ candidates

    private fun updateCandidates() {
        if (!::candidates.isInitialized) return
        val labels = ArrayList<String>()
        val commits = ArrayList<String>()
        var highlight = -1
        when {
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
                val r = english?.suggest(typed)
                result = r
                val auto = if (autoCorrect && scene.autoCorrect && typed != rejected) r?.autoCorrect else null
                if (auto != null) {
                    labels += "“$typed”"; commits += typed
                    labels += auto; commits += auto; highlight = 1
                } else {
                    labels += typed; commits += typed; highlight = 0
                }
                r?.suggestions?.forEach { s ->
                    if (s.text != typed && s.text != auto) { labels += s.text; commits += s.text }
                }
            }
            scene.kind == FieldKind.EMAIL -> emailSuggestions(labels, commits)
            scene.kind == FieldKind.URL -> urlSuggestions(labels, commits)
        }
        if (!scene.suggestions && !chinese) { labels.clear(); commits.clear(); highlight = -1 }
        if (labels.isEmpty() && chinese && RimeEngine.state == RimeEngine.State.DEPLOYING) {
            labels += "正在准备中文词库（仅首次）…"; commits += ""
        }
        shownCandidates = labels
        candidateCommits = commits
        candidates.setItems(labels, highlight)
        candidates.expandable = zhComposing && labels.size > 3
        if (!zhComposing && candidates.expanded) setPanelExpanded(false)
    }

    private fun emailSuggestions(labels: MutableList<String>, commits: MutableList<String>) {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        val token = before.substringAfterLast(' ')
        val at = token.lastIndexOf('@')
        if (at < 0) {
            if (token.isNotEmpty()) { labels += "@"; commits += "@" }
            return
        }
        val typedDomain = token.substring(at + 1).lowercase()
        for (d in EMAIL_DOMAINS) if (d.startsWith(typedDomain) && d != typedDomain) {
            labels += "@$d"; commits += d.substring(typedDomain.length)
        }
    }

    private fun urlSuggestions(labels: MutableList<String>, commits: MutableList<String>) {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        if (before.isEmpty()) {
            for (s in listOf("https://", "www.", "http://")) { labels += s; commits += s }
        } else if (!before.endsWith("/") && before.substringAfterLast('/').let { it.isNotEmpty() && '.' !in it }) {
            for (s in listOf(".com", ".cn", ".net", ".org")) { labels += s; commits += s }
        }
    }

    private fun pickCandidate(index: Int) {
        lastEditAt = SystemClock.uptimeMillis()
        val ic = currentInputConnection ?: return
        if (zhComposing) {
            val ai = aiSuggestion
            if (ai != null && index == 0) { commitAi(); return }
            // Map the bar index back to librime's list (the AI suggestion, if any, is not in it).
            val text = candidateCommits.getOrNull(index)
            val rimeIndex = zh.candidates.indexOfFirst { it.text == text }.takeIf { it >= 0 } ?: index
            if (ai != null) AiRanker.feedback(pickedAi = false, pickedRime = rimeIndex == 0)
            RimeEngine.select(rimeIndex) // may commit, or leave the rest of a long input composing
            zhSync()
            return
        }
        val text = candidateCommits.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        lastAutoCorrect = null
        if (composing.isNotEmpty()) {
            ic.commitText(text, 1)
            if (!chinese && scene.learn) english?.learn(text)
            composing.clear(); result = null
            if (scene.spaceAfterPick && !chinese) ic.commitText(" ", 1)
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
        // Deployment finished (or failed): refresh the space label and any waiting composition.
        if (::keyboard.isInitialized && chinese) { refreshLayout(); updateCandidates() }
    }

    private fun zhKey(code: Int) {
        lastEditAt = SystemClock.uptimeMillis()
        RimeEngine.processKey(code)
        zhSync()
    }

    /** Mirrors librime's commit and composition into the editor and the candidate bar. */
    private fun zhSync() {
        lastEditAt = SystemClock.uptimeMillis()
        val ic = currentInputConnection
        val commit = RimeEngine.takeCommit()
        zh = RimeEngine.snapshot()
        if (commit != null) { ic?.commitText(commit, 1); AiRanker.remember(commit) }
        if (zh.input != aiInput) aiSuggestion = null
        if (zh.composing) ic?.setComposingText(zhDisplay(), 1)
        else if (commit == null) ic?.commitText("", 1) // composition emptied by backspace
        updateCandidates()
        updateT9Side()
        scheduleAi()
    }

    // ------------------------------------------------------------------ AI re-ranking

    /** Sentence the LM prefers over librime's first choice, valid while zh.input == aiInput. */
    private var aiSuggestion: String? = null
    private var aiInput: String = ""
    private val aiWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai").apply { priority = Thread.MIN_PRIORITY }
    }
    private val aiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var aiPending: Runnable? = null
    /** Bumped on every composition change; a job whose generation is old is stale and skipped. */
    private val aiGeneration = java.util.concurrent.atomic.AtomicLong()

    private fun aiEnabled(): Boolean = prefs.getBoolean(KEY_AI, true) && AiRanker.hasModel(this)

    /**
     * After the user pauses briefly, ask the LM about the current input. Only long-enough pinyin
     * input with no partial selection is considered, and stale results are dropped — so typing
     * speed is never affected and the model runs only at natural pauses (battery).
     */
    private fun scheduleAi() {
        aiPending?.let { aiHandler.removeCallbacks(it) }
        val gen = aiGeneration.incrementAndGet()
        AiRanker.abort() // anything still running is about an older input
        if (!zhComposing || !aiEnabled()) return
        val schema = RimeEngine.currentSchema() ?: return
        if (!AiRanker.supports(schema)) return
        val shown = zhDisplay()
        if (shown.any { it in '㐀'..'鿿' }) return // user already picked part of it
        val syllables = AiRanker.syllableCount(shown)
        if (syllables < 3) return
        val input = zh.input
        val before = currentInputConnection?.getTextBeforeCursor(48 + shown.length, 0)?.toString().orEmpty()
        val context = before.dropLast(shown.length.coerceAtMost(before.length))
        val job = Runnable {
            aiWorker.execute { try {
                if (gen != aiGeneration.get() || !AiRanker.ensureLoaded(applicationContext)) return@execute
                val s = AiRanker.suggest(context, schema, input, syllables) { gen != aiGeneration.get() } ?: return@execute
                aiHandler.post {
                    if (zh.input == input && zhComposing) {
                        aiSuggestion = s.text; aiInput = input
                        updateCandidates()
                    }
                }
            } catch (t: Throwable) { android.util.Log.e("ImeService", "AI suggestion failed", t) }
            }
        }
        aiPending = job
        aiHandler.postDelayed(job, 250)
    }

    private fun commitAi() {
        val text = aiSuggestion ?: return
        lastEditAt = SystemClock.uptimeMillis()
        RimeEngine.clear()
        zh = EMPTY_ZH
        aiSuggestion = null
        currentInputConnection?.commitText(text, 1)
        AiRanker.feedback(pickedAi = true, pickedRime = false)
        AiRanker.remember(text)
        setPanelExpanded(false)
        updateCandidates()
        updateT9Side()
    }

    /** Inline text while composing. 九宫格 input is digits, so show the top candidate's pinyin. */
    private fun zhDisplay(): String {
        if (isT9()) {
            val (head, tail) = T9.split(zh.input)
            val spelled = zh.candidates.firstOrNull()?.comment?.takeIf { it.isNotBlank() }
            if (spelled != null && tail.isNotEmpty()) return spelled
            if (tail.isEmpty()) return head.replace('\'', ' ')
        }
        return zh.preedit.ifEmpty { zh.input }
    }

    /** Opens/closes the full candidate grid in place of the keys. */
    private fun setPanelExpanded(open: Boolean) {
        if (!::candidatePanel.isInitialized) return
        val show = open && zhComposing
        candidates.expanded = show
        if (show) {
            candidatePanel.layoutParams = candidatePanel.layoutParams.apply { height = keyboard.height }
            candidatePanel.setItems(RimeEngine.snapshot(300).candidates.map { it.text })
        }
        syncStack()
    }

    // ------------------------------------------------------------------ 九宫格

    private var emojiShown = false

    private fun isT9(): Boolean = chinese && page == Page.LETTERS && RimeEngine.currentSchema() == "t9"

    /** One place decides which of keys / 九宫格 / emoji / candidate grid is visible. */
    private fun syncStack() {
        if (!::keyboard.isInitialized) return
        val panelOn = candidates.expanded && !emojiShown
        val t9On = isT9() && !emojiShown && !panelOn
        emoji.visibility = if (emojiShown) View.VISIBLE else View.GONE
        candidatePanel.visibility = if (panelOn) View.VISIBLE else View.GONE
        t9.visibility = if (t9On) View.VISIBLE else View.GONE
        // The QWERTY view stays laid out (INVISIBLE) so the stack keeps its height.
        keyboard.visibility = if (!emojiShown && !panelOn && !t9On) View.VISIBLE else View.INVISIBLE
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
            // Pin the first digits to this syllable by re-feeding the edited input.
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
        if (!zhComposing) { setPanelExpanded(false); return }
        RimeEngine.select(index)
        zhSync()
        setPanelExpanded(false)
    }

    /** Ends the composition with the highlighted candidate. */
    private fun zhCommitTop() {
        if (!zhComposing) return
        if (zh.candidates.isEmpty()) { zhCommitRaw(); return }
        if (aiSuggestion != null) commitAi() else zhKey(Rime.XK_SPACE)
    }

    /** Ends the composition keeping the typed letters (九宫格: the spelled pinyin, not digits). */
    private fun zhCommitRaw() {
        if (!zhComposing) return
        val raw = if (isT9()) zhDisplay().replace(" ", "") else zh.input
        RimeEngine.clear()
        zh = EMPTY_ZH
        currentInputConnection?.commitText(raw, 1)
        updateCandidates()
    }

    // ------------------------------------------------------------------ emoji

    private fun showEmoji(show: Boolean) {
        if (!::emoji.isInitialized) return
        emojiShown = show
        if (show) {
            emoji.layoutParams = emoji.layoutParams.apply { height = keyboard.height }
            emoji.onShown()
        }
        syncStack()
    }

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onEmojiDelete() = onDeleteDown(false)

    override fun onEmojiClose() {
        showEmoji(false)
        updateAutoShift()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The model is mmapped, but its context buffers are not: give them back under pressure.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) aiWorker.execute { AiRanker.unload() }
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_CHINESE = "chinese"
        const val KEY_SCHEMA = "schema"
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
