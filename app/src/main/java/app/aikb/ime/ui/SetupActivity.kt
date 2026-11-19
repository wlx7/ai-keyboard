package app.aikb.ime.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.aikb.ime.ImeService
import app.aikb.ime.OtpNotificationService
import app.aikb.ime.engine.AiRanker
import app.aikb.ime.engine.AutoSpacer
import app.aikb.ime.engine.ChinesePredictor
import app.aikb.ime.engine.EmojiPredictor
import app.aikb.ime.engine.EnglishDictionary
import app.aikb.ime.engine.EnglishEngine
import app.aikb.ime.engine.HandwritingEngine
import app.aikb.ime.engine.PersonalDictionary
import app.aikb.ime.engine.RecentImageStore
import app.aikb.ime.engine.RimeEngine
import app.aikb.ime.engine.UserHistory
import app.aikb.ime.engine.UserZhLexicon
import app.aikb.ime.keyboard.*
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

private enum class PreviewMode(val label: String) {
    KEYBOARD("拼音键盘"),
    HANDWRITING("手写面板"),
    T9("九宫格"),
}

class SetupActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var selected by mutableStateOf(false)
    private var imagePermGranted by mutableStateOf(false)
    private var otpNotifGranted by mutableStateOf(false)

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true && granted) {
            finish()
        }
    }

    private val imgPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        imagePermGranted = RecentImageStore.hasImagePermission(this)
        if (intent?.getBooleanExtra(EXTRA_REQUEST_IMAGES, false) == true && imagePermGranted) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        imagePermGranted = RecentImageStore.hasImagePermission(this)
        otpNotifGranted = OtpNotificationService.isNotificationAccessEnabled(this)
        UserZhLexicon.ensureLoaded(this)
        RimeEngine.start(this, prefsSchema())
        setContent { AppTheme { Screen() } }
        handlePermissionExtras(intent)
    }

    private fun prefsSchema(): String? =
        getSharedPreferences(ImeService.PREFS, Context.MODE_PRIVATE).getString(ImeService.KEY_SCHEMA, "rime_ice")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePermissionExtras(intent)
    }

    private fun handlePermissionExtras(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else if (intent?.getBooleanExtra(EXTRA_REQUEST_IMAGES, false) == true) {
            imgPermissionLauncher.launch(RecentImageStore.requiredPermissions())
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
        const val EXTRA_REQUEST_IMAGES = "request_images"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    private fun refresh() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        selected = if (Build.VERSION.SDK_INT >= 34) {
            imm.currentInputMethodInfo?.packageName == packageName
        } else {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
                .startsWith("$packageName/")
        }
        imagePermGranted = RecentImageStore.hasImagePermission(this)
        otpNotifGranted = OtpNotificationService.isNotificationAccessEnabled(this)
    }

    @Composable
    private fun Screen() {
        val prefs = remember { getSharedPreferences(ImeService.PREFS, Context.MODE_PRIVATE) }
        var kbPrefsState by remember { mutableStateOf(KeyboardPrefs.from(prefs)) }
        var previewExpanded by remember { mutableStateOf(true) }
        var previewMode by remember {
            val initHw = prefs.getBoolean(ImeService.KEY_HANDWRITING, false)
            val initSchema = prefs.getString(ImeService.KEY_SCHEMA, "rime_ice")
            mutableStateOf(
                when {
                    initHw -> PreviewMode.HANDWRITING
                    initSchema == "t9" -> PreviewMode.T9
                    else -> PreviewMode.KEYBOARD
                },
            )
        }
        var lastChangedKey by remember { mutableStateOf<String?>(null) }

        DisposableEffect(prefs) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                kbPrefsState = KeyboardPrefs.from(p)
                lastChangedKey = key
                when (key) {
                    KeyboardPrefs.KEY_HW_HEIGHT_SCALE,
                    KeyboardPrefs.KEY_HW_STROKE_DP,
                    KeyboardPrefs.KEY_HW_AUTOCOMMIT_MS,
                    -> {
                        previewMode = PreviewMode.HANDWRITING
                        previewExpanded = true
                    }
                    ImeService.KEY_HANDWRITING -> {
                        if (p.getBoolean(ImeService.KEY_HANDWRITING, false)) {
                            previewMode = PreviewMode.HANDWRITING
                            previewExpanded = true
                        }
                    }
                    ImeService.KEY_SCHEMA -> {
                        val sc = p.getString(ImeService.KEY_SCHEMA, "rime_ice")
                        previewMode = if (sc == "t9") PreviewMode.T9 else PreviewMode.KEYBOARD
                        previewExpanded = true
                    }
                    KeyboardPrefs.KEY_NUMBER_ROW,
                    KeyboardPrefs.KEY_SHOW_HINTS,
                    KeyboardPrefs.KEY_ENTER_TO_SEND,
                    KeyboardPrefs.KEY_OTP_AUTOFILL,
                    KeyboardPrefs.KEY_CLIPBOARD_ENABLED,
                    -> {
                        if (previewMode == PreviewMode.HANDWRITING) previewMode = PreviewMode.KEYBOARD
                        previewExpanded = true
                    }
                    KeyboardPrefs.KEY_THEME_ID,
                    KeyboardPrefs.KEY_BORDERS,
                    KeyboardPrefs.KEY_RADIUS_DP,
                    KeyboardPrefs.KEY_HEIGHT_SCALE,
                    KeyboardPrefs.KEY_WIDTH_RATIO,
                    KeyboardPrefs.KEY_ONE_HAND_ALIGN,
                    KeyboardPrefs.KEY_BOTTOM_OFFSET_DP,
                    KeyboardPrefs.KEY_FONT_SCALE,
                    KeyboardPrefs.KEY_CAND_FONT_SCALE,
                    -> {
                        previewExpanded = true
                    }
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                HeroBanner()
                Spacer(Modifier.height(18.dp))

                if (!enabled || !selected) {
                    SectionTitle("快速启用")
                    CardBox {
                        StepRow(1, "在系统设置中启用 Keylo", done = enabled) {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        }
                        RowDivider()
                        StepRow(2, "切换 Keylo 为当前默认输入法", done = selected, enabledRow = enabled) {
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                SectionTitle("实时打字与功能沙盒")
                CardBox {
                    TryField("点此唤起真实输入法打字测试（下方调参面板会自动避让）…", KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                    RowDivider()
                    TryField("浏览器网址 / 搜索框体验", KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search))
                    RowDivider()
                    TryField("邮箱与 1Password 自动填充体验", KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                }

                Spacer(Modifier.height(22.dp))
                ThemeAndStyleSection(prefs)

                Spacer(Modifier.height(22.dp))
                SizeAndErgonomicsSection(prefs)

                Spacer(Modifier.height(22.dp))
                AuxiliaryInputSection(
                    prefs = prefs,
                    otpNotifGranted = otpNotifGranted,
                    onOpenNotifAccess = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    },
                )

                Spacer(Modifier.height(22.dp))
                LayoutAndFeelSection(prefs)

                Spacer(Modifier.height(22.dp))
                HandwritingCustomSection(prefs)

                Spacer(Modifier.height(22.dp))
                ChineseSchemaSection(prefs)

                Spacer(Modifier.height(22.dp))
                PersonalDictionarySection(prefs)

                Spacer(Modifier.height(22.dp))
                AiSection(prefs)

                Spacer(Modifier.height(22.dp))
                AutofillAndImageSection(
                    prefs = prefs,
                    imagePermGranted = imagePermGranted,
                    onRequestImagePerm = { imgPermissionLauncher.launch(RecentImageStore.requiredPermissions()) },
                )

                Spacer(Modifier.height(22.dp))
                PrivacySection(prefs)
                Spacer(Modifier.height(24.dp))
            }

            if (!imeVisible) {
                LiveKeyboardPreviewDock(
                    kbPrefs = kbPrefsState,
                    mode = previewMode,
                    expanded = previewExpanded,
                    lastChangedKey = lastChangedKey,
                    onModeChange = {
                        previewMode = it
                        previewExpanded = true
                    },
                    onToggleExpanded = { previewExpanded = !previewExpanded },
                )
            }
        }
    }

    @Composable
    private fun HeroBanner() {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E40AF), Color(0xFF2563EB), Color(0xFF4F46E5)),
                    ),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keylo", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) {
                        Text("v3.0 Hybrid Octagram + LLM", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "零网络权限 · 内置 Octagram 毫秒级语法词图（支持万象 .gram 导入）· Q8_0 端侧大模型 · 实时自造词学习",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.90f),
                )
            }
        }
    }
}

/**
 * Sticky bottom WYSIWYG live keyboard preview dock:
 *   1. Shows a coherent simulated input strip (`上文 + 当前预编辑`) right above the keyboard so
 *      preedit, candidates, and context match 1:1 with zero visual disconnect.
 *   2. Directly interactive: tapping keys or drawing handwriting strokes runs the real offline
 *      Rime / English / Handwriting / Pangu-spacing / Emoji-prediction engines live!
 */
@Composable
private fun LiveKeyboardPreviewDock(
    kbPrefs: KeyboardPrefs,
    mode: PreviewMode,
    expanded: Boolean,
    lastChangedKey: String?,
    onModeChange: (PreviewMode) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    var simCommitted by remember { mutableStateOf("秋水") }
    var simComposing by remember { mutableStateOf("shi zhi") }
    var isCustomSession by remember { mutableStateOf(false) }
    var resetSeq by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(thickness = 0.8.dp, color = Secondary().copy(alpha = 0.25f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "实时预览",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                PreviewMode.entries.forEach { m ->
                    val active = expanded && mode == m
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            )
                            .clickable { onModeChange(m) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            m.label,
                            fontSize = 11.5.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    if (expanded) "收起 ⌄" else "展开预览 ▲",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (expanded) {
            // Coherent simulated input field showing exact committed context + underlined composing span
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val annotated = buildAnnotatedString {
                    append(simCommitted)
                    if (simComposing.isNotEmpty()) {
                        withStyle(
                            SpanStyle(
                                color = primaryColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium,
                            ),
                        ) {
                            append(simComposing)
                        }
                    }
                    withStyle(SpanStyle(color = primaryColor, fontWeight = FontWeight.Bold)) {
                        append("▏")
                    }
                }
                Text(
                    text = annotated,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (isCustomSession) {
                    Text(
                        "重置示例",
                        fontSize = 11.5.sp,
                        color = primaryColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { resetSeq++ }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                } else {
                    Text(
                        "可直接按键试打",
                        fontSize = 11.sp,
                        color = Secondary(),
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    PreviewKeyboardHostView(ctx) { committed, composing, custom ->
                        simCommitted = committed
                        simComposing = composing
                        isCustomSession = custom
                    }
                },
                update = { view -> view.bind(kbPrefs, mode, lastChangedKey, resetSeq) },
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            )
        }
    }
}

/**
 * Interactive Canvas host mirroring `ImeService.onCreateInputView()` 1:1:
 *   - Default showcase pairs context `"秋水"` + preedit `"shi zhi"` -> `["✦ 时至", "是指", "实质", "市值", "食指"]`.
 *   - Tapping keys or handwriting strokes drives real `RimeEngine` / `EnglishEngine` / `HandwritingEngine` live!
 */
private class PreviewKeyboardHostView(
    context: Context,
    private val onSimState: (committed: String, composing: String, isCustom: Boolean) -> Unit,
) : FrameLayout(context) {
    private val previewOverlay = PreviewOverlay(context)
    private val preeditBar = PreeditBar(context)
    private val candidateBar = CandidateBar(context) { idx -> onPickCandidate(idx) }
    private val column: LinearLayout
    private val keyboardView: KeyboardView
    private val handwritingView: HandwritingView
    private val t9View: T9View

    private var currentPrefs = KeyboardPrefs()
    private var currentMode = PreviewMode.KEYBOARD
    private var lastResetSeq = 0
    private var lastHandledKey: String? = null
    private var previewChinese = true
    private var previewPage = Page.LETTERS
    private var previewShift = ShiftState.OFF

    // Live interactive state
    private var userInteractive = false
    private var simCommitted = "秋水"
    private val rawInput = StringBuilder()
    private var currentCandidates: List<String> = emptyList()
    private var currentCommits: List<String> = emptyList()
    private var lastSpaceMs = 0L

    private val enEngine: EnglishEngine? by lazy {
        runCatching {
            EnglishEngine(
                EnglishDictionary.load(context),
                UserHistory(context.filesDir.resolve("en_user.tsv")),
            )
        }.getOrNull()
    }
    private val zhPredictor: ChinesePredictor? by lazy {
        runCatching { ChinesePredictor.load(context, context.filesDir.resolve("zh_user.tsv")) }.getOrNull()
    }
    private val bgWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun triggerPreviewHaptic() {
        if (!currentPrefs.haptics || currentPrefs.hapticStrengthMs <= 0) return
        val vib = vibrator
        if (vib != null && vib.hasVibrator()) {
            val ms = currentPrefs.hapticStrengthMs.toLong().coerceIn(4L, 60L)
            val amp = (currentPrefs.hapticStrengthMs * 4.5f).toInt().coerceIn(35, 255)
            runCatching {
                vib.vibrate(VibrationEffect.createOneShot(ms, amp))
                return
            }
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun playFeedback(key: Key) {
        triggerPreviewHaptic()
        if (currentPrefs.sound) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val fx = when (key.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am?.playSoundEffect(fx, -1f)
        }
    }

    private fun enterInteractiveIfNeeded(clearShowcase: Boolean = true) {
        if (!userInteractive) {
            userInteractive = true
            if (clearShowcase) simCommitted = ""
            rawInput.clear()
        }
    }

    private fun appendFormattedCommit(text: String) {
        if (text.isEmpty()) return
        val formatted = AutoSpacer.formatCommit(
            simCommitted,
            text,
            currentPrefs.spaceCnEn,
            currentPrefs.spaceCnNum,
        )
        simCommitted = (simCommitted + formatted).takeLast(36)
    }

    private fun onPickCandidate(idx: Int) {
        triggerPreviewHaptic()
        val picked = currentCommits.getOrNull(idx)?.takeIf { it.isNotEmpty() } ?: return
        if (!userInteractive) {
            userInteractive = true
        }
        rawInput.clear()
        handwritingView.clearCanvas(notify = false)
        appendFormattedCommit(picked)
        if (!previewChinese) simCommitted += " "
        refreshInteractiveCandidates(showPostPredict = true)
    }

    private fun formatSpelledPinyin(s: String): String {
        if (s.isEmpty()) return ""
        // Segment cleanly into readable syllables for PreeditBar
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            var matched = 1
            for (len in minOf(6, s.length - i) downTo 1) {
                val sub = s.substring(i, i + len)
                if (PinyinCorrectorPlaceholder.isComplete(sub)) {
                    matched = len
                    break
                }
            }
            out += s.substring(i, i + matched)
            i += matched
        }
        return out.joinToString(" ")
    }

    private object PinyinCorrectorPlaceholder {
        fun isComplete(s: String): Boolean = app.aikb.ime.engine.PinyinCorrector.minCompleteSyllables(s) == 1
    }

    private fun refreshInteractiveCandidates(showPostPredict: Boolean = false) {
        candidateBar.freshOtpCode = null
        if (rawInput.isNotEmpty()) {
            val typed = rawInput.toString()
            if (previewChinese) {
                val spelled = formatSpelledPinyin(typed)
                preeditBar.text = spelled
                val learned = UserZhLexicon.lookup("rime_ice", typed, simCommitted, AiRanker.sessionMemory())
                val rimeCands = if (RimeEngine.isReady) {
                    RimeEngine.queryTop("rime_ice", typed, maxCands = 6).ifEmpty { listOf(typed) }
                } else {
                    listOf(typed)
                }
                val labels = ArrayList<String>()
                val commits = ArrayList<String>()
                learned.topPriority.forEach { w ->
                    if (w !in commits) {
                        labels += if (w in learned.contextSparkleSet) "✦ $w" else w
                        commits += w
                    }
                }
                rimeCands.forEach { w ->
                    if (w !in commits) {
                        labels += w
                        commits += w
                    }
                }
                currentCandidates = labels
                currentCommits = commits
                candidateBar.expandable = labels.size > 3
                candidateBar.setItems(labels, 0)
                onSimState(simCommitted, spelled, true)
            } else {
                preeditBar.text = ""
                val r = enEngine?.suggest(typed)
                val labels = ArrayList<String>()
                val commits = ArrayList<String>()
                val auto = if (currentPrefs.autoCorrect) r?.autoCorrect else null
                var hi = 0
                if (auto != null) {
                    labels += "“$typed”"; commits += typed
                    labels += auto; commits += auto; hi = 1
                } else {
                    labels += typed; commits += typed
                }
                r?.suggestions?.forEach { s ->
                    if (s.text != typed && s.text != auto && labels.size < 6) {
                        labels += s.text; commits += s.text
                    }
                }
                currentCandidates = labels
                currentCommits = commits
                candidateBar.expandable = false
                candidateBar.setItems(labels, hi)
                onSimState(simCommitted, typed, true)
            }
            return
        }

        preeditBar.text = ""
        if (showPostPredict && currentPrefs.postCommitPredict && simCommitted.isNotEmpty()) {
            val emojis = if (previewChinese) EmojiPredictor.predictForChinese(simCommitted)
            else EmojiPredictor.predictForEnglish(simCommitted.trim().substringAfterLast(' '))
            val words = if (previewChinese) zhPredictor?.predict(simCommitted, max = 5).orEmpty()
            else enEngine?.predictNext(simCommitted.trim().substringAfterLast(' '), max = 5).orEmpty()
            val merged = buildList {
                emojis.firstOrNull()?.let { add(it) }
                words.forEachIndexed { i, w ->
                    if (w !in this) add(w)
                    if (i == 0 && emojis.size > 1 && emojis[1] !in this) add(emojis[1])
                }
            }
            currentCandidates = merged
            currentCommits = merged
            candidateBar.expandable = false
            candidateBar.setItems(merged, -1)
        } else {
            currentCandidates = emptyList()
            currentCommits = emptyList()
            candidateBar.expandable = false
            candidateBar.setItems(emptyList(), -1)
        }
        onSimState(simCommitted, "", true)
    }

    private val kbListener = object : KeyboardView.Listener {
        override fun onText(key: Key, text: String) {
            enterInteractiveIfNeeded(clearShowcase = true)
            if (previewPage == Page.LETTERS && text.length == 1 && text[0].isLetter()) {
                val ch = if (previewChinese) text[0].lowercaseChar() else text[0]
                if (!previewChinese && rawInput.isEmpty() && AutoSpacer.needsLeadingSpace(simCommitted, ch, currentPrefs.spaceCnEn, currentPrefs.spaceCnNum)) {
                    simCommitted += " "
                }
                rawInput.append(ch)
                if (previewShift == ShiftState.ONCE) {
                    previewShift = ShiftState.OFF
                    keyboardView.shift = previewShift
                }
                refreshInteractiveCandidates()
            } else {
                if (rawInput.isNotEmpty()) {
                    val top = currentCommits.getOrNull(if (!previewChinese && currentCommits.size > 1) 1 else 0) ?: rawInput.toString()
                    rawInput.clear()
                    appendFormattedCommit(top)
                }
                appendFormattedCommit(text)
                refreshInteractiveCandidates(showPostPredict = false)
            }
        }

        override fun onFunction(key: Key) {
            when (key.type) {
                KeyType.SHIFT -> {
                    previewShift = if (previewShift == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
                    keyboardView.shift = previewShift
                }
                KeyType.LANG -> {
                    rawInput.clear()
                    previewChinese = !previewChinese
                    syncKeyboardFace()
                    if (userInteractive) refreshInteractiveCandidates() else applyCoherentShowcase(null)
                }
                KeyType.SPACE -> {
                    enterInteractiveIfNeeded(clearShowcase = false)
                    val now = android.os.SystemClock.uptimeMillis()
                    if (rawInput.isNotEmpty()) {
                        val pickIdx = if (!previewChinese && currentPrefs.autoCorrect && currentCommits.size > 1 && currentCandidates[0].startsWith("“")) 1 else 0
                        val chosen = currentCommits.getOrNull(pickIdx) ?: rawInput.toString()
                        rawInput.clear()
                        appendFormattedCommit(chosen)
                        if (!previewChinese) simCommitted += " "
                        refreshInteractiveCandidates(showPostPredict = true)
                    } else if (currentPrefs.doubleSpacePeriod && now - lastSpaceMs < 450 && simCommitted.endsWith(" ") && simCommitted.length >= 2) {
                        simCommitted = simCommitted.dropLast(1) + if (previewChinese) "。" else ". "
                        lastSpaceMs = 0L
                        refreshInteractiveCandidates(showPostPredict = false)
                        return
                    } else {
                        simCommitted += " "
                        refreshInteractiveCandidates(showPostPredict = false)
                    }
                    lastSpaceMs = now
                }
                KeyType.ENTER -> {
                    enterInteractiveIfNeeded(clearShowcase = false)
                    if (rawInput.isNotEmpty()) {
                        appendFormattedCommit(rawInput.toString())
                        rawInput.clear()
                    }
                    refreshInteractiveCandidates(showPostPredict = false)
                }
                KeyType.TO_SYMBOLS -> {
                    previewPage = Page.SYMBOLS
                    syncKeyboardFace()
                }
                KeyType.TO_SYMBOLS2 -> {
                    previewPage = Page.SYMBOLS2
                    syncKeyboardFace()
                }
                KeyType.TO_LETTERS -> {
                    previewPage = Page.LETTERS
                    syncKeyboardFace()
                }
                else -> Unit
            }
        }

        override fun onDeleteDown(repeat: Boolean) {
            enterInteractiveIfNeeded(clearShowcase = false)
            if (rawInput.isNotEmpty()) {
                rawInput.deleteCharAt(rawInput.length - 1)
                refreshInteractiveCandidates()
            } else if (simCommitted.isNotEmpty()) {
                val cpLen = Character.charCount(Character.codePointBefore(simCommitted, simCommitted.length))
                simCommitted = simCommitted.dropLast(cpLen)
                refreshInteractiveCandidates()
            }
        }

        override fun onCursorMove(steps: Int) = Unit
        override fun onLongPressFunction(key: Key) = Unit
        override fun onKeyDownFeedback(key: Key) = playFeedback(key)
    }

    private val hwListener = object : HandwritingView.Listener {
        override fun onHandwritingStrokes(strokes: List<List<HandwritingEngine.Point>>) {
            userInteractive = true
            bgWorker.execute {
                val cands = HandwritingEngine.getOrLoad(context.applicationContext)
                    ?.recognize(strokes, topK = 10, preferChinese = true)
                    .orEmpty()
                post {
                    if (cands.isNotEmpty()) {
                        currentCandidates = cands
                        currentCommits = cands
                        candidateBar.expandable = cands.size > 3
                        candidateBar.setItems(cands, 0)
                        onSimState(simCommitted, cands.first(), true)
                    }
                }
            }
        }

        override fun onHandwritingAutoCommitPrevious() {
            val top = currentCommits.firstOrNull() ?: return
            appendFormattedCommit(top)
            currentCandidates = emptyList(); currentCommits = emptyList()
            onSimState(simCommitted, "", true)
        }

        override fun onHandwritingClear() {
            if (userInteractive) {
                currentCandidates = emptyList(); currentCommits = emptyList()
                candidateBar.setItems(emptyList(), -1)
                onSimState(simCommitted, "", true)
            }
        }

        override fun onHandwritingPunct(punct: String) {
            enterInteractiveIfNeeded(clearShowcase = true)
            appendFormattedCommit(punct)
            onSimState(simCommitted, "", true)
        }

        override fun onHandwritingExitToKeyboard() = Unit
        override fun onHandwritingCycleHeight() = Unit
        override fun onFunction(key: Key) = kbListener.onFunction(key)
        override fun onDeleteDown(repeat: Boolean) = kbListener.onDeleteDown(repeat)
        override fun onKeyDownFeedback(key: Key) = playFeedback(key)
    }

    private val t9Listener = object : T9View.Listener {
        override fun onT9Digit(digit: Char) = Unit
        override fun onT9Separator() = Unit
        override fun onT9Side(index: Int) = Unit
        override fun onT9Clear() = Unit
        override fun onFunction(key: Key) = kbListener.onFunction(key)
        override fun onDeleteDown(repeat: Boolean) = kbListener.onDeleteDown(repeat)
        override fun onKeyDownFeedback(key: Key) = playFeedback(key)
    }

    init {
        candidateBar.showImageIcon = false
        keyboardView = KeyboardView(context, kbListener).also { it.previewOverlay = previewOverlay }
        handwritingView = HandwritingView(context, hwListener).apply {
            previewDemoMode = true
            visibility = View.GONE
        }
        t9View = T9View(context, t9Listener).apply {
            sideItems = listOf("shi", "zhi", "si", "zi", "，", "。")
            spaceLabel = "选定"
            visibility = View.GONE
        }

        val stack = FrameLayout(context).apply {
            addView(keyboardView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(handwritingView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(t9View, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(preeditBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(candidateBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(stack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(previewOverlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bgWorker.shutdownNow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) applySqueezePadding(w)
    }

    private fun applySqueezePadding(hostWidth: Int) {
        val squeezePx = ((1f - currentPrefs.widthRatio) * hostWidth).toInt().coerceAtLeast(0)
        val padL = when {
            currentPrefs.oneHandAlign < 0 -> 0
            currentPrefs.oneHandAlign > 0 -> squeezePx
            else -> squeezePx / 2
        }
        val padR = squeezePx - padL
        val bottomPx = (currentPrefs.bottomOffsetDp * resources.displayMetrics.density).toInt()
        column.setPadding(padL, 0, padR, bottomPx)
    }

    private fun syncKeyboardFace() {
        val layout = when (previewPage) {
            Page.LETTERS -> Layouts.letters(
                BottomRow.TEXT,
                previewChinese,
                if (previewChinese) "全拼" else null,
                numberRow = currentPrefs.numberRow,
            )
            Page.SYMBOLS -> Layouts.symbols(BottomRow.TEXT, previewChinese)
            Page.SYMBOLS2 -> Layouts.symbols2(BottomRow.TEXT, previewChinese)
            else -> Layouts.letters(BottomRow.TEXT, previewChinese, "全拼", numberRow = currentPrefs.numberRow)
        }
        keyboardView.layout = layout
        keyboardView.chinese = previewChinese
        keyboardView.shift = previewShift
        keyboardView.hints = if (previewPage == Page.LETTERS && currentPrefs.showKeyHints) {
            Layouts.symbolHints(previewChinese, currentPrefs.numberRow)
        } else {
            emptyMap()
        }
        val enterLbl = if (currentPrefs.enterToSend) {
            (if (previewChinese) "发送" else "send") to true
        } else {
            (if (previewChinese) "换行" else "return") to false
        }
        keyboardView.enterLabel = enterLbl.first
        keyboardView.enterAccent = enterLbl.second
        handwritingView.enterLabel = enterLbl.first
        handwritingView.enterAccent = enterLbl.second
        t9View.enterLabel = enterLbl.first
        t9View.enterAccent = enterLbl.second
    }

    /**
     * Populates a 100% coherent input <-> preedit <-> candidate showcase tailored to the active mode & setting.
     */
    private fun applyCoherentShowcase(changedKey: String?) {
        val prefs = currentPrefs
        val mode = currentMode
        when {
            changedKey == KeyboardPrefs.KEY_OTP_AUTOFILL && prefs.otpAutoFill && mode != PreviewMode.HANDWRITING -> {
                simCommitted = "短信验证码："
                preeditBar.text = ""
                candidateBar.expandable = false
                candidateBar.freshOtpCode = "2046"
                candidateBar.freshOtpSource = "信息"
                currentCandidates = emptyList(); currentCommits = listOf("2046")
                candidateBar.setItems(emptyList(), -1)
                onSimState(simCommitted, "", false)
            }
            changedKey == KeyboardPrefs.KEY_CLIPBOARD_ENABLED && mode != PreviewMode.HANDWRITING -> {
                simCommitted = if (prefs.clipboardEnabled) "顶栏剪贴板入口已开启" else "顶栏剪贴板入口已隐藏"
                preeditBar.text = ""
                candidateBar.expandable = false
                candidateBar.freshOtpCode = null
                currentCandidates = emptyList(); currentCommits = emptyList()
                candidateBar.setItems(emptyList(), -1)
                onSimState(simCommitted, "", false)
            }
            changedKey == KeyboardPrefs.KEY_POST_PREDICT && mode != PreviewMode.HANDWRITING -> {
                simCommitted = "生日快乐"
                preeditBar.text = ""
                candidateBar.freshOtpCode = null
                candidateBar.expandable = false
                if (prefs.postCommitPredict) {
                    val assoc = listOf("🎂", "天天开心", "🎉", "万事如意", "身体健康")
                    currentCandidates = assoc; currentCommits = assoc
                    candidateBar.setItems(assoc, -1)
                } else {
                    currentCandidates = emptyList(); currentCommits = emptyList()
                    candidateBar.setItems(emptyList(), -1)
                }
                onSimState(simCommitted, "", false)
            }
            mode == PreviewMode.HANDWRITING -> {
                simCommitted = ""
                preeditBar.text = ""
                candidateBar.freshOtpCode = null
                candidateBar.expandable = true
                val hwSample = listOf("永", "水", "咏", "泳", "求")
                currentCandidates = hwSample; currentCommits = hwSample
                candidateBar.setItems(hwSample, 0)
                onSimState("", "永", false)
            }
            !previewChinese -> {
                simCommitted = "Good "
                preeditBar.text = ""
                candidateBar.freshOtpCode = null
                candidateBar.expandable = false
                val enSample = listOf("morn", "morning", "mornings", "more")
                currentCandidates = enSample; currentCommits = enSample
                candidateBar.setItems(enSample, 1)
                onSimState("Good ", "morn", false)
            }
            else -> {
                // Coherent AI contextual disambiguation showcase: "秋水" + "shi zhi" -> "✦ 时至"
                simCommitted = "秋水"
                preeditBar.text = "shi zhi"
                candidateBar.freshOtpCode = null
                candidateBar.expandable = true
                val labels = listOf("✦ 时至", "是指", "实质", "市值", "食指")
                val commits = listOf("时至", "是指", "实质", "市值", "食指")
                currentCandidates = labels; currentCommits = commits
                candidateBar.setItems(labels, 0)
                onSimState("秋水", "shi zhi", false)
            }
        }
    }

    fun bind(prefs: KeyboardPrefs, mode: PreviewMode, lastChangedKey: String?, resetSeq: Int) {
        val prev = currentPrefs
        val modeChanged = currentMode != mode
        val resetTriggered = resetSeq != lastResetSeq
        val keyTriggeredShowcase = lastChangedKey != lastHandledKey && lastChangedKey in setOf(
            KeyboardPrefs.KEY_OTP_AUTOFILL,
            KeyboardPrefs.KEY_CLIPBOARD_ENABLED,
            KeyboardPrefs.KEY_POST_PREDICT,
        )
        currentPrefs = prefs
        currentMode = mode
        lastResetSeq = resetSeq
        lastHandledKey = lastChangedKey

        if (resetTriggered || modeChanged || keyTriggeredShowcase) {
            userInteractive = false
            rawInput.clear()
            handwritingView.clearCanvas(notify = false)
        }

        val t = KbTheme.resolve(context, prefs)
        setBackgroundColor(t.background)
        preeditBar.theme = t
        candidateBar.theme = t
        keyboardView.theme = t
        handwritingView.theme = t
        t9View.theme = t
        previewOverlay.theme = t

        candidateBar.prefs = prefs
        candidateBar.showClipboardIcon = prefs.clipboardEnabled
        keyboardView.prefs = prefs
        handwritingView.prefs = prefs
        t9View.prefs = prefs

        syncKeyboardFace()

        keyboardView.visibility = if (mode == PreviewMode.KEYBOARD) View.VISIBLE else View.GONE
        handwritingView.visibility = if (mode == PreviewMode.HANDWRITING) View.VISIBLE else View.GONE
        t9View.visibility = if (mode == PreviewMode.T9) View.VISIBLE else View.GONE

        if (userInteractive) {
            refreshInteractiveCandidates()
        } else {
            applyCoherentShowcase(lastChangedKey)
        }

        val hostW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        applySqueezePadding(hostW)

        if (modeChanged ||
            prev.heightScale != prefs.heightScale ||
            prev.hwHeightScale != prefs.hwHeightScale ||
            prev.numberRow != prefs.numberRow ||
            prev.bottomOffsetDp != prefs.bottomOffsetDp ||
            prev.widthRatio != prefs.widthRatio
        ) {
            keyboardView.requestLayout()
            handwritingView.requestLayout()
            t9View.requestLayout()
            column.requestLayout()
            requestLayout()
        }
    }
}

@Composable
private fun ThemeAndStyleSection(prefs: SharedPreferences) {
    var selectedTheme by remember { mutableStateOf(prefs.getString(KeyboardPrefs.KEY_THEME_ID, "system") ?: "system") }
    var keyBorders by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_BORDERS, true)) }
    var radiusDp by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_RADIUS_DP, 7.5f)) }

    SectionTitle("主题皮肤与视觉风格")
    CardBox {
        Column(Modifier.padding(14.dp)) {
            val rows = KbTheme.PRESETS.chunked(4)
            rows.forEachIndexed { rIdx, rowItems ->
                if (rIdx > 0) Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in rowItems) {
                        val active = preset.id == selectedTheme
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(preset.swatchBg))
                                .border(
                                    width = if (active) 2.2.dp else 0.8.dp,
                                    color = if (active) Color(preset.swatchAccent) else Color.Gray.copy(alpha = 0.28f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable {
                                    selectedTheme = preset.id
                                    prefs.edit().putString(KeyboardPrefs.KEY_THEME_ID, preset.id).apply()
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(16.dp, 12.dp).clip(RoundedCornerShape(3.dp)).background(Color(preset.swatchKey)))
                                Box(Modifier.size(10.dp, 12.dp).clip(RoundedCornerShape(3.dp)).background(Color(preset.swatchAccent)))
                            }
                            Spacer(Modifier.height(6.dp))
                            val darkSwatch = preset.id in setOf("obsidian_dark", "cyber_indigo")
                            Text(
                                preset.name,
                                fontSize = 11.5.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (darkSwatch) Color.White else Color(0xFF1E293B),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        RowDivider()
        SwitchItem(
            title = "按键立体边框与投影",
            subtitle = "关闭即切换为 Gboard 同款无边框纯平极简风",
            checked = keyBorders,
        ) {
            keyBorders = it
            prefs.edit().putBoolean(KeyboardPrefs.KEY_BORDERS, it).apply()
        }
        RowDivider()
        SliderItem(
            title = "按键圆角弧度",
            valueText = "${radiusDp.roundToInt()} dp",
            value = radiusDp,
            range = 4f..14f,
            onValueChange = {
                radiusDp = it
                prefs.edit().putFloat(KeyboardPrefs.KEY_RADIUS_DP, it).apply()
            },
        )
    }
}

@Composable
private fun SizeAndErgonomicsSection(prefs: SharedPreferences) {
    var heightScale by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_HEIGHT_SCALE, 1.00f)) }
    var widthRatio by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_WIDTH_RATIO, 1.00f)) }
    var align by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_ONE_HAND_ALIGN, 0)) }
    var bottomOffset by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_BOTTOM_OFFSET_DP, 0).toFloat()) }
    var keyFont by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_FONT_SCALE, 1.00f)) }
    var candFont by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_CAND_FONT_SCALE, 1.00f)) }

    SectionTitle("键盘大小与单手模式（下方实时预览）")
    CardBox {
        SliderItem(
            title = "键盘整体高度",
            valueText = "${(heightScale * 100).roundToInt()}%",
            value = heightScale,
            range = 0.75f..1.35f,
            onValueChange = {
                heightScale = it
                prefs.edit().putFloat(KeyboardPrefs.KEY_HEIGHT_SCALE, it).apply()
            },
        )
        RowDivider()
        SliderItem(
            title = "键盘宽度（单手内缩比例）",
            valueText = "${(widthRatio * 100).roundToInt()}%",
            value = widthRatio,
            range = 0.76f..1.00f,
            onValueChange = {
                widthRatio = it
                prefs.edit().putFloat(KeyboardPrefs.KEY_WIDTH_RATIO, it).apply()
            },
        )
        if (widthRatio < 0.99f) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(-1 to "← 单手靠左", 0 to "居中", 1 to "单手靠右 →").forEach { (v, label) ->
                    val act = align == v
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (act) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .clickable {
                                align = v
                                prefs.edit().putInt(KeyboardPrefs.KEY_ONE_HAND_ALIGN, v).apply()
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 13.sp, color = if (act) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        RowDivider()
        SliderItem(
            title = "底部抬高间距（全面屏防误触）",
            valueText = "${bottomOffset.roundToInt()} dp",
            value = bottomOffset,
            range = 0f..40f,
            onValueChange = {
                bottomOffset = it
                prefs.edit().putInt(KeyboardPrefs.KEY_BOTTOM_OFFSET_DP, it.roundToInt()).apply()
            },
        )
        RowDivider()
        SliderItem(
            title = "按键字母与文字大小",
            valueText = "${(keyFont * 100).roundToInt()}%",
            value = keyFont,
            range = 0.85f..1.25f,
            onValueChange = {
                keyFont = it
                prefs.edit().putFloat(KeyboardPrefs.KEY_FONT_SCALE, it).apply()
            },
        )
        RowDivider()
        SliderItem(
            title = "候选词栏文字大小",
            valueText = "${(candFont * 100).roundToInt()}%",
            value = candFont,
            range = 0.85f..1.30f,
            onValueChange = {
                candFont = it
                prefs.edit().putFloat(KeyboardPrefs.KEY_CAND_FONT_SCALE, it).apply()
            },
        )
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    heightScale = 1.00f; widthRatio = 1.00f; align = 0; bottomOffset = 0f; keyFont = 1.00f; candFont = 1.00f
                    prefs.edit()
                        .putFloat(KeyboardPrefs.KEY_HEIGHT_SCALE, 1.00f)
                        .putFloat(KeyboardPrefs.KEY_WIDTH_RATIO, 1.00f)
                        .putInt(KeyboardPrefs.KEY_ONE_HAND_ALIGN, 0)
                        .putInt(KeyboardPrefs.KEY_BOTTOM_OFFSET_DP, 0)
                        .putFloat(KeyboardPrefs.KEY_FONT_SCALE, 1.00f)
                        .putFloat(KeyboardPrefs.KEY_CAND_FONT_SCALE, 1.00f)
                        .apply()
                }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("一键恢复默认尺寸与字号", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
    Text(
        "提示：打字时也可点击键盘左上角「⊞ → 键盘大小」直接在实时键盘上拖拽调节。",
        fontSize = 12.5.sp, color = Secondary(), modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 14.dp),
    )
}

/**
 * Complete 9-item WeChat Keyboard (`微信键盘`) "辅助输入" feature parity card.
 */
@Composable
private fun AuxiliaryInputSection(
    prefs: SharedPreferences,
    otpNotifGranted: Boolean,
    onOpenNotifAccess: () -> Unit,
) {
    val context = LocalContext.current
    var enterSend by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_ENTER_TO_SEND, false)) }
    var spaceCnNum by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SPACE_CN_NUM, false)) }
    var spaceCnEn by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SPACE_CN_EN, false)) }
    var otpAutoFill by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_OTP_AUTOFILL, true)) }
    var clipboardOn by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_CLIPBOARD_ENABLED, true)) }
    var autoCaps by remember { mutableStateOf(prefs.getBoolean("auto_caps", true)) }
    var doubleSpace by remember { mutableStateOf(prefs.getBoolean("double_space", true)) }
    var smartZh by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SMART_PINYIN_CORRECT, true)) }
    var autoEn by remember { mutableStateOf(prefs.getBoolean("auto_correct", true)) }
    var postPredict by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_POST_PREDICT, true)) }

    var fuzzyOn by remember { mutableStateOf(prefs.getBoolean(RimeEngine.PREF_FUZZY_ENABLED, false)) }
    var fuzzySelected by remember {
        mutableStateOf(prefs.getStringSet(RimeEngine.PREF_FUZZY_PAIRS, RimeEngine.COMMON_FUZZY_IDS)!!.toSet())
    }
    var fuzzyExpanded by remember { mutableStateOf(false) }
    var rimeState by remember { mutableStateOf(RimeEngine.state) }

    DisposableEffect(Unit) {
        val cb = { rimeState = RimeEngine.state }
        RimeEngine.addStateListener(cb)
        onDispose { RimeEngine.removeStateListener(cb) }
    }

    fun applyFuzzy(nextOn: Boolean, nextSet: Set<String>) {
        fuzzyOn = nextOn
        fuzzySelected = nextSet
        prefs.edit()
            .putBoolean(RimeEngine.PREF_FUZZY_ENABLED, nextOn)
            .putStringSet(RimeEngine.PREF_FUZZY_PAIRS, HashSet(nextSet))
            .apply()
        RimeEngine.redeployFuzzy(context)
    }

    SectionTitle("辅助输入（微信键盘同款全对齐）")
    CardBox {
        SwitchItem(
            title = "回车键发送",
            subtitle = "开启后多行聊天框右下角变为蓝色「发送」，长按回车仍可换行",
            checked = enterSend,
        ) {
            enterSend = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_ENTER_TO_SEND, it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "中文和数字间加空格",
            subtitle = "示例：你好 2024",
            checked = spaceCnNum,
        ) {
            spaceCnNum = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SPACE_CN_NUM, it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "中文和英文间加空格",
            subtitle = "示例：你好 Hello（保留 5G / iPhone16 不拆散）",
            checked = spaceCnEn,
        ) {
            spaceCnEn = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SPACE_CN_EN, it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "验证码自动提取",
            subtitle = "收到短信或复制验证码后在顶栏显示「🔑 2046 · 来自信息」，90 秒纯内存即焚",
            checked = otpAutoFill,
        ) {
            otpAutoFill = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_OTP_AUTOFILL, it).apply()
        }
        if (otpAutoFill) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenNotifAccess)
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "短信通知自动提取权限（不开启仍支持剪贴板提取）",
                    fontSize = 13.sp,
                    color = Secondary(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (otpNotifGranted) "已开启 ✓" else "去开启 ›",
                    fontSize = 13.5.sp,
                    color = if (otpNotifGranted) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        RowDivider()
        SwitchItem(
            title = "剪贴板历史与快捷粘贴",
            subtitle = "关闭后不再监听系统剪贴板、隐藏顶栏剪贴板入口并清空暂存记录",
            checked = clipboardOn,
        ) {
            clipboardOn = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_CLIPBOARD_ENABLED, it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "英文键盘 - 首字母自动大写",
            subtitle = "句首或换行后自动大写首个英文字母",
            checked = autoCaps,
        ) {
            autoCaps = it; prefs.edit().putBoolean("auto_caps", it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "双击空格输入句号",
            subtitle = "中文键盘双击空格输入「。」，英文键盘双击输入「. 」",
            checked = doubleSpace,
        ) {
            doubleSpace = it; prefs.edit().putBoolean("double_space", it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "模糊拼音（z=zh / c=ch / n=l 等 12 组）",
            subtitle = when {
                !fuzzyOn -> "支持平翘舌、前后鼻音等 12 组模糊音自定义组合"
                rimeState == RimeEngine.State.DEPLOYING -> "正在后台更新模糊音索引…"
                else -> "已启用 ${fuzzySelected.size} 组模糊音"
            },
            checked = fuzzyOn,
        ) { on ->
            val nextSet = if (on && fuzzySelected.isEmpty()) RimeEngine.COMMON_FUZZY_IDS else fuzzySelected
            applyFuzzy(on, nextSet)
            if (on) fuzzyExpanded = true
        }
        if (fuzzyOn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { fuzzyExpanded = !fuzzyExpanded }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("配置模糊拼音明细（z/zh、c/ch、s/sh、in/ing…）", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(if (fuzzyExpanded) "收起 ▲" else "设置 ▼", fontSize = 13.sp, color = Secondary())
            }
            if (fuzzyExpanded) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "常用南方模糊音预设",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { applyFuzzy(true, RimeEngine.COMMON_FUZZY_IDS) },
                    )
                    Text(
                        "全选 12 组",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { applyFuzzy(true, RimeEngine.FUZZY_PAIRS.map { it.id }.toSet()) },
                    )
                }
                RimeEngine.FUZZY_PAIRS.forEach { pair ->
                    RowDivider()
                    val checked = pair.id in fuzzySelected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (checked) fuzzySelected - pair.id else fuzzySelected + pair.id
                                applyFuzzy(true, next)
                            }
                            .padding(horizontal = 16.dp, vertical = 9.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${pair.group} · ${pair.label}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (checked) Text("✓", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        RowDivider()
        SwitchItem(
            title = "中文键盘 - 拼音智能纠错",
            subtitle = "修正邻键误触与双字母倒序（如 nihso → 你好，zhognguo → 中国）",
            checked = smartZh,
        ) {
            smartZh = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SMART_PINYIN_CORRECT, it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "英文键盘 - 单词自动改正",
            subtitle = "示例：Helip → Hello，thebest → the best（退格键一键撤回原词）",
            checked = autoEn,
        ) {
            autoEn = it; prefs.edit().putBoolean("auto_correct", it).apply()
        }
        RowDivider()
        SwitchItem(
            title = "输入后联想（含应景表情联想）",
            subtitle = "示例：生日快乐 → 🎂 天天开心",
            checked = postPredict,
        ) {
            postPredict = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_POST_PREDICT, it).apply()
        }
    }
}

@Composable
private fun LayoutAndFeelSection(prefs: SharedPreferences) {
    var numRow by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_NUMBER_ROW, false)) }
    var showHints by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SHOW_HINTS, true)) }
    var keyPreview by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_PREVIEW, true)) }
    var longPressMs by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_LONG_PRESS_MS, 270).toFloat()) }
    var haptics by remember { mutableStateOf(prefs.getBoolean("haptics", true)) }
    var hapticMs by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_HAPTIC_MS, 16).toFloat()) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", false)) }
    var spaceSwipe by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SPACE_SWIPE, true)) }
    var swipeDel by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SWIPE_DELETE, true)) }

    SectionTitle("按键布局、手势与触感")
    CardBox {
        SwitchItem("常驻顶部独立数字行", "开启后字母键盘变为 5 行，顶排直接输入 1-0", numRow) {
            numRow = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_NUMBER_ROW, it).apply()
        }
        RowDivider()
        SwitchItem("按键长按符号副标提示", "在字母键右上角显示数字与常用标点，长按直接输入", showHints) {
            showHints = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SHOW_HINTS, it).apply()
        }
        RowDivider()
        SliderItem(
            title = "长按符号触发延迟",
            valueText = "${longPressMs.roundToInt()} ms",
            value = longPressMs,
            range = 160f..420f,
            onValueChange = {
                longPressMs = it; prefs.edit().putInt(KeyboardPrefs.KEY_LONG_PRESS_MS, it.roundToInt()).apply()
            },
        )
        RowDivider()
        SwitchItem("按键气泡放大预览", "按下字母时弹出清晰气泡提示", keyPreview) {
            keyPreview = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_PREVIEW, it).apply()
        }
        RowDivider()
        SwitchItem("滑动空格键移动光标", "在空格键上左右滑动精准定位文本光标", spaceSwipe) {
            spaceSwipe = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SPACE_SWIPE, it).apply()
        }
        RowDivider()
        SwitchItem("左滑退格键快速重输 / 删整词", "从 ⌫ 键向左轻滑一键清空当前拼音或删除整词", swipeDel) {
            swipeDel = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SWIPE_DELETE, it).apply()
        }
        RowDivider()
        SwitchItem("按键振动反馈", null, haptics) {
            haptics = it; prefs.edit().putBoolean("haptics", it).apply()
        }
        if (haptics) {
            SliderItem(
                title = "振动清脆强度",
                valueText = "${hapticMs.roundToInt()} ms",
                value = hapticMs,
                range = 5f..45f,
                onValueChange = {
                    hapticMs = it; prefs.edit().putInt(KeyboardPrefs.KEY_HAPTIC_MS, it.roundToInt()).apply()
                },
            )
        }
        RowDivider()
        SwitchItem("按键音效", null, sound) {
            sound = it; prefs.edit().putBoolean("sound", it).apply()
        }
    }
}

@Composable
private fun HandwritingCustomSection(prefs: SharedPreferences) {
    var hwHeight by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_HW_HEIGHT_SCALE, 1.24f)) }
    var hwStroke by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_HW_STROKE_DP, 4.4f)) }
    var hwDelay by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_HW_AUTOCOMMIT_MS, 800).toFloat()) }

    SectionTitle("手写面板定制（拖动滑块自动切换手写预览）")
    CardBox {
        SliderItem(
            title = "手写画布高度比例",
            valueText = "${(hwHeight * 100).roundToInt()}%",
            value = hwHeight,
            range = 1.00f..1.55f,
            onValueChange = {
                hwHeight = it; prefs.edit().putFloat(KeyboardPrefs.KEY_HW_HEIGHT_SCALE, it).apply()
            },
        )
        RowDivider()
        SliderItem(
            title = "手写笔迹粗细",
            valueText = "${"%.1f".format(hwStroke)} dp",
            value = hwStroke,
            range = 2.5f..7.0f,
            onValueChange = {
                hwStroke = it; prefs.edit().putFloat(KeyboardPrefs.KEY_HW_STROKE_DP, it).apply()
            },
        )
        RowDivider()
        SliderItem(
            title = "抬笔自动定字间隔",
            valueText = "${hwDelay.roundToInt()} ms",
            value = hwDelay,
            range = 450f..1500f,
            onValueChange = {
                hwDelay = it; prefs.edit().putInt(KeyboardPrefs.KEY_HW_AUTOCOMMIT_MS, it.roundToInt()).apply()
            },
        )
    }
}

@Composable
private fun ChineseSchemaSection(prefs: SharedPreferences) {
    var schema by remember { mutableStateOf(prefs.getString(ImeService.KEY_SCHEMA, "rime_ice")!!) }
    var handwritingSelected by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_HANDWRITING, false)) }
    var trad by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_TRADITIONAL, false)) }

    val modeOptions = remember {
        buildList {
            RimeEngine.SCHEMAS.forEachIndexed { idx, pair ->
                add(pair)
                if (idx == 1) add("handwriting" to "手写输入（全字集生僻字 · 连写 / 叠写）")
            }
        }
    }

    SectionTitle("中文输入方案与简繁切换")
    CardBox {
        SwitchItem("输出繁体中文", null, trad) {
            trad = it; prefs.edit().putBoolean(ImeService.KEY_TRADITIONAL, it).apply()
        }
    }

    Spacer(Modifier.height(12.dp))
    CardBox {
        modeOptions.forEachIndexed { i, (id, name) ->
            if (i > 0) RowDivider()
            val isChecked = if (id == "handwriting") handwritingSelected else (!handwritingSelected && id == schema)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (id == "handwriting") {
                            handwritingSelected = true
                            prefs.edit().putBoolean(ImeService.KEY_HANDWRITING, true).putBoolean(ImeService.KEY_CHINESE, true).apply()
                        } else {
                            handwritingSelected = false
                            schema = id
                            prefs.edit()
                                .putBoolean(ImeService.KEY_HANDWRITING, false)
                                .putBoolean(ImeService.KEY_CHINESE, true)
                                .putString(ImeService.KEY_SCHEMA, id)
                                .apply()
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (isChecked) Text("✓", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PersonalDictionarySection(prefs: SharedPreferences) {
    val context = LocalContext.current
    val dictFile = remember { context.filesDir.resolve("personal_dict.tsv") }
    var entries by remember { mutableStateOf(PersonalDictionary.allEntries(context)) }
    var zhLearnedCount by remember { mutableStateOf(UserZhLexicon.learnedCount(context)) }
    var enCount by remember { mutableStateOf(UserHistory(context.filesDir.resolve("en_user.tsv")).totalWordCount()) }
    var showAddForm by remember { mutableStateOf(false) }
    var showAllEntries by remember { mutableStateOf(false) }
    var shortcutInput by remember { mutableStateOf("") }
    var wordInput by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    fun notifyImeReload() {
        entries = PersonalDictionary.allEntries(context)
        zhLearnedCount = UserZhLexicon.learnedCount(context)
        enCount = UserHistory(context.filesDir.resolve("en_user.tsv")).totalWordCount()
        prefs.edit().putLong("reload_user_lexicon", System.currentTimeMillis()).apply()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        statusMsg = "正在导入词库文件…"
        Thread {
            val res = runCatching {
                val enHist = UserHistory(context.filesDir.resolve("en_user.tsv"))
                val zhPred = runCatching {
                    ChinesePredictor.load(context, context.filesDir.resolve("zh_user.tsv"))
                }.getOrNull()
                context.contentResolver.openInputStream(uri)!!.use { stream ->
                    PersonalDictionary.importFromStream(dictFile, stream, enHist, zhPred)
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                res.onSuccess { summary ->
                    notifyImeReload()
                    statusMsg = "导入成功：新增自定义词条 ${summary.importedCustom} 条、英文词频 ${summary.importedEnglishWords} 条、中文联想 ${summary.importedChineseBigrams} 组"
                }.onFailure {
                    statusMsg = "导入失败：${it.message}"
                }
            }
        }.start()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        statusMsg = "正在打包导出 Gboard 兼容词库…"
        Thread {
            val res = runCatching {
                val enHist = UserHistory(context.filesDir.resolve("en_user.tsv"))
                val zhPred = runCatching {
                    ChinesePredictor.load(context, context.filesDir.resolve("zh_user.tsv"))
                }.getOrNull()
                val rimeWords = RimeEngine.exportSyncedUserWords(context)
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    PersonalDictionary.exportGboardZip(dictFile, out, enHist, zhPred, rimeWords)
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                res.onSuccess { total ->
                    statusMsg = "导出完成：共备份 $total 条个人词条（标准 Gboard .zip 格式，可双向导入）"
                }.onFailure {
                    statusMsg = "导出失败：${it.message}"
                }
            }
        }.start()
    }

    SectionTitle("个人词典与词库导入导出（兼容 Gboard）")
    CardBox {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("本地个人词库概览", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "自定义短语 ${entries.size} 条 · 已自学习中文词组 $zhLearnedCount 条 · 英文个人词条 $enCount 条（长按候选词可移除误学词条）",
                    fontSize = 12.sp,
                    color = Secondary(),
                )
            }
        }
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showAddForm = !showAddForm }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showAddForm) "收起添加面板 ▲" else "+ 添加自定义词条 / 快捷短语",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
        if (showAddForm) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = shortcutInput,
                    onValueChange = { shortcutInput = it },
                    label = { Text("快捷码 / 拼音（选填，如 dz、eml、weilele）", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = wordInput,
                    onValueChange = { wordInput = it },
                    label = { Text("要输出的词条、专有名词或长短语", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (PersonalDictionary.addOrUpdate(context, shortcutInput, wordInput)) {
                                shortcutInput = ""
                                wordInput = ""
                                showAddForm = false
                                notifyImeReload()
                                statusMsg = "已保存到个人词典 ✓"
                            }
                        },
                        enabled = wordInput.isNotBlank(),
                    ) {
                        Text("保存词条")
                    }
                    TextButton(onClick = { showAddForm = false }) {
                        Text("取消")
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showAllEntries = !showAllEntries }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("管理已添加的自定义词条（${entries.size}）", fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(if (showAllEntries) "收起 ▲" else "查看 ▼", fontSize = 13.sp, color = Secondary())
            }
            if (showAllEntries) {
                entries.take(30).forEach { item ->
                    RowDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.word, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            if (item.shortcut.isNotEmpty()) {
                                Text("快捷码: ${item.shortcut}  ·  ${item.locale}", fontSize = 12.sp, color = Secondary())
                            }
                        }
                        Text(
                            "删除",
                            fontSize = 13.5.sp,
                            color = Color(0xFFEF4444),
                            modifier = Modifier
                                .clickable {
                                    PersonalDictionary.remove(context, item)
                                    notifyImeReload()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { importLauncher.launch(arrayOf("*/*")) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("导入个人词库（Gboard .zip / txt / Rime 词表）…", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                Text("直接兼容 Gboard 导出的 PersonalDictionary.zip 及纯文本词库", fontSize = 12.sp, color = Secondary())
            }
        }
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { exportLauncher.launch("Keylo-PersonalDictionary.zip") }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("导出个人词库备份（Gboard 兼容 .zip）…", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                Text("一键打包自定义短语与中英文已学习词条，可导入 Gboard 或新手机", fontSize = 12.sp, color = Secondary())
            }
        }
    }
    if (statusMsg != null) {
        Text(statusMsg!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 14.dp))
    }
}

@Composable
private fun AiSection(prefs: SharedPreferences) {
    val context = LocalContext.current
    val userFile = remember { AiRanker.modelFile(context) }
    val customGramFile = remember { RimeEngine.customGramFile(context) }
    val hasBuiltin = remember { AiRanker.hasBuiltinModel(context) }
    var userSize by remember { mutableStateOf(if (userFile.exists()) userFile.length() else 0L) }
    var customGramSize by remember {
        mutableStateOf(if (RimeEngine.isValidGramFile(customGramFile)) customGramFile.length() else 0L)
    }
    var gramOn by remember { mutableStateOf(prefs.getBoolean(RimeEngine.PREF_GRAMMAR_ENABLED, true)) }
    var aiOn by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_AI, true)) }
    var status by remember { mutableStateOf<String?>(null) }

    val gramPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "正在校验并导入语法模型（.gram）…"
        Thread {
            val result = runCatching {
                customGramFile.parentFile?.mkdirs()
                val tmp = File(customGramFile.path + ".part")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val head = ByteArray(14)
                    val stream = BufferedInputStream(input)
                    stream.mark(32)
                    val readN = stream.read(head)
                    stream.reset()
                    require(readN == 14 && String(head, Charsets.US_ASCII) == "Rime::Grammar/") {
                        "不是有效的 Rime/万象 .gram 语法模型文件"
                    }
                    tmp.outputStream().use { stream.copyTo(it, 1 shl 20) }
                }
                Files.move(tmp.toPath(), customGramFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                customGramFile.length()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.onSuccess { sz ->
                    customGramSize = sz
                    gramOn = true
                    prefs.edit().putBoolean(RimeEngine.PREF_GRAMMAR_ENABLED, true).apply()
                    RimeEngine.redeployGrammar(context)
                    status = "外部万象语法模型已导入并激活（${sz / 1_000_000} MB）"
                }.onFailure { status = "语法模型导入失败：${it.message}" }
            }
        }.start()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "正在导入外部大模型…"
        Thread {
            val result = runCatching {
                AiRanker.resetAndUnload()
                userFile.parentFile?.mkdirs()
                val tmp = File(userFile.path + ".part")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val head = ByteArray(4)
                    val stream = BufferedInputStream(input)
                    stream.mark(8); stream.read(head); stream.reset()
                    require(String(head, Charsets.US_ASCII) == "GGUF") { "不是有效的 GGUF 模型文件" }
                    tmp.outputStream().use { stream.copyTo(it, 1 shl 20) }
                }
                Files.move(tmp.toPath(), userFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                userFile.length()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.onSuccess { userSize = it; status = "外部 GGUF 大模型已导入并激活" }
                    .onFailure { status = "导入失败：${it.message}" }
            }
        }.start()
    }

    SectionTitle("双擎智能核心（Octagram 毫秒级语法词图 + 端侧大模型）")
    CardBox {
        SwitchItem(
            "第一层：Octagram 0ms 词图语法模型",
            "敲击瞬间实时优化词组搭配与成语整句（如 qiushuishizhi → 秋水时至）",
            gramOn,
        ) {
            gramOn = it
            prefs.edit().putBoolean(RimeEngine.PREF_GRAMMAR_ENABLED, it).apply()
            RimeEngine.redeployGrammar(context)
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前语法库", fontSize = 15.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            val gramDesc = when {
                !gramOn -> "已关闭"
                customGramSize > 0 -> "外部万象模型 · ${customGramSize / 1_000_000} MB"
                else -> "内置 Keylo-460K 语法库 · 已启用"
            }
            Text(gramDesc, fontSize = 14.sp, color = if (gramOn) Color(0xFF16A34A) else Secondary(), fontWeight = FontWeight.Medium)
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clickable { gramPicker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                Text("导入外部万象语法模型（.gram）…", fontSize = 15.5.sp, color = MaterialTheme.colorScheme.primary)
                Text("直接兼容 wanxiang-lts-zh-hans.gram，自动校验文件头防闪退", fontSize = 12.sp, color = Secondary())
            }
        }
        if (customGramSize > 0) {
            RowDivider()
            Row(
                Modifier.fillMaxWidth().clickable {
                    customGramFile.delete()
                    customGramSize = 0L
                    RimeEngine.redeployGrammar(context)
                    status = "已移除外部 .gram 并恢复内置 Keylo-460K 语法库"
                }.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("移除外部 .gram 并恢复内置语法库", fontSize = 15.sp, color = Color(0xFFEF4444))
            }
        }
        RowDivider()
        SwitchItem(
            "第二层：端侧大模型跨句语义选句",
            "Q8_0 高精词嵌入 + 25 亿字频先验校准，精准理解跨句长上文且杜绝生僻字胡推",
            aiOn,
        ) {
            aiOn = it; prefs.edit().putBoolean(ImeService.KEY_AI, it).apply()
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前大模型", fontSize = 15.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            val modelDesc = when {
                userSize > 0 -> "自定义模型 · ${userSize / 1_000_000} MB"
                hasBuiltin -> "内置旗舰 Keylo-285M v2 (Q8_0) · 已就绪"
                else -> "未安装（请导入 .gguf）"
            }
            Text(modelDesc, fontSize = 14.sp, color = if (userSize > 0 || hasBuiltin) Color(0xFF16A34A) else Secondary(), fontWeight = FontWeight.Medium)
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Text(
                if (userSize > 0 || hasBuiltin) "导入其他自定义大模型（.gguf）…" else "导入模型文件（.gguf）…",
                fontSize = 15.5.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (userSize > 0) {
            RowDivider()
            Row(
                Modifier.fillMaxWidth().clickable {
                    AiRanker.resetAndUnload()
                    userFile.delete()
                    userSize = 0
                    status = if (hasBuiltin) "已恢复使用内置旗舰模型" else "已移除外部模型"
                }.padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text(
                    if (hasBuiltin) "移除外部模型并恢复内置 Keylo-285M v2" else "删除已导入模型",
                    fontSize = 15.5.sp,
                    color = Color(0xFFEF4444),
                )
            }
        }
    }
    if (status != null) {
        Text(status!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 14.dp, top = 6.dp))
    }
}

@Composable
private fun AutofillAndImageSection(
    prefs: SharedPreferences,
    imagePermGranted: Boolean,
    onRequestImagePerm: () -> Unit,
) {
    var shotBubble by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SCREENSHOT_BUBBLE, true)) }

    SectionTitle("1Password 自动填充与最新截图发送")
    CardBox {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("1Password / Bitwarden 内联自动填充", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Text("已原生支持 Android 11+ 内联填充条，聚焦账号密码框时自动在键盘顶栏显示填充芯片", fontSize = 12.sp, color = Secondary())
            }
            Text("已启用", fontSize = 14.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.SemiBold)
        }
        RowDivider()
        SwitchItem("最新截图 / 照片顶栏快捷发送气泡", "截屏后 90 秒内在键盘顶栏弹出缩略图，点一下直传聊天框", shotBubble) {
            shotBubble = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SCREENSHOT_BUBBLE, it).apply()
        }
        RowDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !imagePermGranted, onClick = onRequestImagePerm)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("本地截图 / 相册读取权限", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                if (imagePermGranted) "已授权 ✓" else "点击授权 ›",
                fontSize = 15.sp,
                color = if (imagePermGranted) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PrivacySection(prefs: SharedPreferences) {
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    SectionTitle("离线隐私保障")
    CardBox {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    UserHistory(context.filesDir.resolve("en_user.tsv")).clear()
                    UserZhLexicon.clearAll(context)
                    AiRanker.clearMemory()
                    context.filesDir.resolve("zh_user.tsv").delete()
                    prefs.edit().putLong("clear_history_at", System.currentTimeMillis()).apply()
                    cleared = true
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(if (cleared) "已清空本地个人词频记录 ✓" else "清除本地已学习的个人词频", color = Color(0xFFEF4444), fontSize = 16.sp)
        }
    }
    Text(
        "Keylo 在 AndroidManifest 中未声明 INTERNET 网络权限，操作系统内核层面阻断一切联网可能。您的所有输入、语音、手写、个人词典、验证码提取、剪贴板与图片均 100% 留在手机本地。",
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        color = Secondary(),
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp),
    )
}

@Composable
private fun Secondary() = if (isSystemInDarkTheme()) Color(0xFF94A3B8) else Color(0xFF64748B)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        background = Color(0xFF0B0D13),
        surface = Color(0xFF161922),
        primary = Color(0xFF3B82F6),
        onBackground = Color(0xFFF1F5F9),
        onSurface = Color(0xFFF1F5F9),
    ) else lightColorScheme(
        background = Color(0xFFF1F5F9),
        surface = Color(0xFFFFFFFF),
        primary = Color(0xFF2563EB),
        onBackground = Color(0xFF0F172A),
        onSurface = Color(0xFF0F172A),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun SectionTitle(s: String) {
    Text(
        s,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = Secondary(),
        modifier = Modifier.padding(start = 12.dp, bottom = 7.dp),
    )
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Secondary().copy(alpha = 0.22f))
}

@Composable
private fun StepRow(n: Int, title: String, done: Boolean, enabledRow: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabledRow, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (done) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary.copy(alpha = if (enabledRow) 1f else 0.35f)),
            contentAlignment = Alignment.Center,
        ) { Text(if (done) "✓" else "$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabledRow) 1f else 0.4f), modifier = Modifier.weight(1f))
        Text(if (done) "已完成" else "›", color = Secondary(), fontSize = if (done) 14.sp else 20.sp)
    }
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = Secondary())
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2563EB), checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun SliderItem(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

@Composable
private fun TryField(hint: String, options: KeyboardOptions) {
    var text by remember { mutableStateOf("") }
    TextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(hint, fontSize = 15.sp) },
        singleLine = options.keyboardType != KeyboardType.Text,
        keyboardOptions = options,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
