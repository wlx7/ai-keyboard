package app.aikb.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.aikb.ime.ImeService
import app.aikb.ime.engine.RimeEngine
import app.aikb.ime.engine.UserHistory

class SetupActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var selected by mutableStateOf(false)

    private val micPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true && granted) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Screen() } }
        if (intent?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh() // returning from the IME picker dialog
    }

    private fun refresh() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        selected = if (android.os.Build.VERSION.SDK_INT >= 34) {
            imm.currentInputMethodInfo?.packageName == packageName
        } else {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
                .startsWith("$packageName/")
        }
    }

    @Composable
    private fun Screen() {
        val prefs = remember { getSharedPreferences(ImeService.PREFS, Context.MODE_PRIVATE) }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("灵犀输入法", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text("完全离线 · 无网络权限 · 你的输入只留在这台手机上", fontSize = 15.sp, color = Secondary())
            Spacer(Modifier.height(24.dp))

            SectionTitle("开始使用")
            Card {
                StepRow(1, "在系统设置中启用灵犀输入法", done = enabled) {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
                Divider()
                StepRow(2, "切换为当前输入法", done = selected, enabledRow = enabled) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("试一试")
            Card {
                TryField("在这里输入…", KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Divider()
                TryField("邮箱", KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                Divider()
                TryField("网址", KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go))
                Divider()
                TryField("数字", KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("中文输入方案")
            var schema by remember { mutableStateOf(prefs.getString(ImeService.KEY_SCHEMA, "rime_ice")!!) }
            var handwritingSelected by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_HANDWRITING, false)) }
            val modeOptions = remember {
                buildList {
                    RimeEngine.SCHEMAS.forEachIndexed { idx, pair ->
                        add(pair)
                        if (idx == 1) add("handwriting" to "手写输入（支持连写 / 叠写）")
                    }
                }
            }
            Card {
                modeOptions.forEachIndexed { i, (id, name) ->
                    if (i > 0) Divider()
                    val isChecked = if (id == "handwriting") handwritingSelected else (!handwritingSelected && id == schema)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (id == "handwriting") {
                                handwritingSelected = true
                                prefs.edit()
                                    .putBoolean(ImeService.KEY_HANDWRITING, true)
                                    .putBoolean(ImeService.KEY_CHINESE, true)
                                    .apply()
                            } else {
                                handwritingSelected = false
                                schema = id
                                prefs.edit()
                                    .putBoolean(ImeService.KEY_HANDWRITING, false)
                                    .putBoolean(ImeService.KEY_CHINESE, true)
                                    .putString(ImeService.KEY_SCHEMA, id)
                                    .apply()
                            }
                        }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (isChecked) Text("✓", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                "也可随时点击键盘顶栏的「✍ 手写」或左上角「⊞」一键切换全拼、九宫格、手写、双拼与五笔。",
                fontSize = 13.sp, color = Secondary(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Card { PrefSwitch(prefs, ImeService.KEY_TRADITIONAL, "输出繁体字", false) }

            Spacer(Modifier.height(24.dp))
            FuzzyPinyinSection(prefs)

            Spacer(Modifier.height(24.dp))
            AiSection(prefs)
            Spacer(Modifier.height(24.dp))
            SectionTitle("键盘")
            Card {
                PrefSwitch(prefs, "auto_correct", "自动纠错", true)
                Divider()
                PrefSwitch(prefs, "auto_caps", "自动大写", true)
                Divider()
                PrefSwitch(prefs, "double_space", "连按两下空格输入句号", true)
                Divider()
                PrefSwitch(prefs, "haptics", "按键振动", true)
                Divider()
                PrefSwitch(prefs, "sound", "按键音", false)
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("隐私")
            var cleared by remember { mutableStateOf(false) }
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        UserHistory(filesDir.resolve("en_user.tsv")).clear()
                        filesDir.resolve("zh_user.tsv").delete()
                        prefs.edit().putLong("clear_history_at", System.currentTimeMillis()).apply()
                        cleared = true
                    }.padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(if (cleared) "已清除" else "清除已学习的词", color = Color(0xFFFF3B30), fontSize = 17.sp)
                }
            }
            Text(
                "本应用未申请网络权限，系统层面无法联网。学习到的词只保存在应用私有目录。",
                fontSize = 13.sp, color = Secondary(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Secondary() = if (isSystemInDarkTheme()) Color(0xFF8E8E93) else Color(0xFF6D6D72)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        background = Color(0xFF000000), surface = Color(0xFF1C1C1E), primary = Color(0xFF0A84FF),
        onBackground = Color.White, onSurface = Color.White,
    ) else lightColorScheme(
        background = Color(0xFFF2F2F7), surface = Color.White, primary = Color(0xFF007AFF),
        onBackground = Color.Black, onSurface = Color.Black,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun SectionTitle(s: String) {
    Text(s.uppercase(), fontSize = 13.sp, color = Secondary(), modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Secondary().copy(alpha = 0.3f))
}

@Composable
private fun StepRow(n: Int, title: String, done: Boolean, enabledRow: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabledRow, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape)
                .background(if (done) Color(0xFF34C759) else MaterialTheme.colorScheme.primary.copy(alpha = if (enabledRow) 1f else 0.35f)),
            contentAlignment = Alignment.Center,
        ) { Text(if (done) "✓" else "$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabledRow) 1f else 0.4f),
            modifier = Modifier.weight(1f))
        Text(if (done) "已完成" else "›", color = Secondary(), fontSize = if (done) 15.sp else 22.sp)
    }
}

@Composable
private fun PrefSwitch(prefs: android.content.SharedPreferences, key: String, title: String, default: Boolean) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = { on = it; prefs.edit().putBoolean(key, it).apply() },
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun TryField(hint: String, options: KeyboardOptions) {
    var text by remember { mutableStateOf("") }
    TextField(
        value = text, onValueChange = { text = it },
        placeholder = { Text(hint) },
        singleLine = options.keyboardType != KeyboardType.Text,
        keyboardOptions = options,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** AI 智能选句: on/off, model status, import/remove of the offline model file. */
@Composable
private fun AiSection(prefs: android.content.SharedPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = remember { app.aikb.ime.engine.AiRanker.modelFile(context) }
    var size by remember { mutableStateOf(if (file.exists()) file.length() else 0L) }
    var status by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "正在导入…"
        Thread {
            val result = runCatching {
                app.aikb.ime.engine.AiRanker.unload()
                file.parentFile?.mkdirs()
                val tmp = java.io.File(file.path + ".part")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    val head = ByteArray(4)
                    val stream = java.io.BufferedInputStream(input)
                    stream.mark(8); stream.read(head); stream.reset()
                    require(String(head, Charsets.US_ASCII) == "GGUF") { "不是 GGUF 模型文件" }
                    tmp.outputStream().use { stream.copyTo(it, 1 shl 20) }
                }
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                file.length()
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                result.onSuccess { size = it; status = "导入完成" }.onFailure { status = "导入失败：${it.message}" }
            }
        }.start()
    }
    SectionTitle("AI 智能选句")
    Card {
        PrefSwitch(prefs, ImeService.KEY_AI, "用本地语言模型优化整句", true)
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("模型", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(if (size > 0) "已安装 · ${size / 1_000_000} MB" else "未安装", fontSize = 17.sp, color = Secondary())
        }
        Divider()
        Row(Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(if (size > 0) "更换模型文件…" else "导入模型文件（.gguf）…", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (size > 0) {
            Divider()
            Row(Modifier.fillMaxWidth().clickable {
                app.aikb.ime.engine.AiRanker.unload(); file.delete(); size = 0; status = "已删除"
            }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("删除模型", fontSize = 17.sp, color = Color(0xFFFF3B30))
            }
        }
    }
    Text(
        (status?.let { "$it。" } ?: "") +
            "模型在手机本地运行，只在停顿时为长拼音挑选更合理的整句，并按你的选择自动微调权重。" +
            "应用不联网，请将模型文件（推荐专属蒸馏版 zh-lm-lingxi-285m-q4_0.gguf 约 155 MB，或轻量版 47 MB / 千问原版 397 MB）拷到手机后在此导入。",
        fontSize = 13.sp, color = Secondary(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
    )
}

@Composable
private fun FuzzyPinyinSection(prefs: android.content.SharedPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var fuzzyOn by remember { mutableStateOf(prefs.getBoolean(RimeEngine.PREF_FUZZY_ENABLED, false)) }
    var selected by remember {
        mutableStateOf(prefs.getStringSet(RimeEngine.PREF_FUZZY_PAIRS, RimeEngine.COMMON_FUZZY_IDS)!!.toSet())
    }
    var rimeState by remember { mutableStateOf(RimeEngine.state) }

    DisposableEffect(Unit) {
        val cb = { rimeState = RimeEngine.state }
        RimeEngine.addStateListener(cb)
        onDispose { RimeEngine.removeStateListener(cb) }
    }

    fun applyFuzzy(nextOn: Boolean, nextSet: Set<String>) {
        fuzzyOn = nextOn
        selected = nextSet
        prefs.edit()
            .putBoolean(RimeEngine.PREF_FUZZY_ENABLED, nextOn)
            .putStringSet(RimeEngine.PREF_FUZZY_PAIRS, HashSet(nextSet))
            .apply()
        RimeEngine.redeployFuzzy(context)
    }

    SectionTitle("模糊拼音")
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("启用模糊拼音", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                if (fuzzyOn) {
                    val sub = if (rimeState == RimeEngine.State.DEPLOYING) "正在编译模糊音词库…"
                    else "已启用 ${selected.size} 组（支持全拼 / 双拼 / 九宫格）"
                    Text(sub, fontSize = 12.sp, color = Secondary())
                }
            }
            Switch(
                checked = fuzzyOn,
                onCheckedChange = { on ->
                    val nextSet = if (on && selected.isEmpty()) RimeEngine.COMMON_FUZZY_IDS else selected
                    applyFuzzy(on, nextSet)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White),
            )
        }
        if (fuzzyOn) {
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "常用南方模糊音预设",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { applyFuzzy(true, RimeEngine.COMMON_FUZZY_IDS) },
                )
                Text(
                    "全选",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { applyFuzzy(true, RimeEngine.FUZZY_PAIRS.map { it.id }.toSet()) },
                )
            }
            RimeEngine.FUZZY_PAIRS.forEach { pair ->
                Divider()
                val checked = pair.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val next = if (checked) selected - pair.id else selected + pair.id
                        applyFuzzy(true, next)
                    }.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${pair.group} · ${pair.label}",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (checked) {
                        Text("✓", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    Text(
        "开启或修改模糊音后会在后台重新生成拼写索引（约数秒），完成后自动生效。",
        fontSize = 13.sp, color = Secondary(), modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
    )
}
