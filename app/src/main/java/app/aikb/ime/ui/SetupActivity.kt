package app.aikb.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aikb.ime.ImeService
import app.aikb.ime.engine.AiRanker
import app.aikb.ime.engine.RecentImageStore
import app.aikb.ime.engine.RimeEngine
import app.aikb.ime.engine.UserHistory
import app.aikb.ime.keyboard.KbTheme
import app.aikb.ime.keyboard.KeyboardPrefs
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

class SetupActivity : ComponentActivity() {
    private var enabled by mutableStateOf(false)
    private var selected by mutableStateOf(false)
    private var imagePermGranted by mutableStateOf(false)

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
        setContent { AppTheme { Screen() } }
        handlePermissionExtras(intent)
    }

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
        selected = if (android.os.Build.VERSION.SDK_INT >= 34) {
            imm.currentInputMethodInfo?.packageName == packageName
        } else {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
                .startsWith("$packageName/")
        }
        imagePermGranted = RecentImageStore.hasImagePermission(this)
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
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            HeroBanner()
            Spacer(Modifier.height(18.dp))

            if (!enabled || !selected) {
                SectionTitle("快速启用")
                CardBox {
                    StepRow(1, "在系统设置中启用灵犀输入法", done = enabled) {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                    RowDivider()
                    StepRow(2, "切换为当前默认输入法", done = selected, enabledRow = enabled) {
                        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            SectionTitle("实时打字与功能沙盒")
            CardBox {
                TryField("点此立即体验（拼音 / 手写 / 纠错 / 缩放）…", KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
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
            LayoutAndFeelSection(prefs)

            Spacer(Modifier.height(22.dp))
            HandwritingCustomSection(prefs)

            Spacer(Modifier.height(22.dp))
            ChineseSchemaSection(prefs)

            Spacer(Modifier.height(22.dp))
            AiSection(prefs)

            Spacer(Modifier.height(22.dp))
            AutofillAndImageSection(
                prefs = prefs,
                imagePermGranted = imagePermGranted,
                onRequestImagePerm = { imgPermissionLauncher.launch(RecentImageStore.requiredPermissions()) },
            )

            Spacer(Modifier.height(22.dp))
            FuzzyPinyinSection(prefs)

            Spacer(Modifier.height(22.dp))
            PrivacySection(prefs)
            Spacer(Modifier.height(36.dp))
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
                    Text("灵犀输入法", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    ) {
                        Text("v2.0 旗舰离线版", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "零网络权限 · 233万搜狗级词库 · 4万生僻字大画布手写 · 端侧AI智能选句 · 1Password内联填充",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color.White.copy(alpha = 0.90f),
                )
            }
        }
    }
}

@Composable
private fun ThemeAndStyleSection(prefs: android.content.SharedPreferences) {
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
private fun SizeAndErgonomicsSection(prefs: android.content.SharedPreferences) {
    var heightScale by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_HEIGHT_SCALE, 1.00f)) }
    var widthRatio by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_WIDTH_RATIO, 1.00f)) }
    var align by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_ONE_HAND_ALIGN, 0)) }
    var bottomOffset by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_BOTTOM_OFFSET_DP, 0).toFloat()) }
    var keyFont by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_FONT_SCALE, 1.00f)) }
    var candFont by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_CAND_FONT_SCALE, 1.00f)) }

    SectionTitle("键盘大小与单手模式（等比无损缩放）")
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

@Composable
private fun LayoutAndFeelSection(prefs: android.content.SharedPreferences) {
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
private fun HandwritingCustomSection(prefs: android.content.SharedPreferences) {
    var hwHeight by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_HW_HEIGHT_SCALE, 1.24f)) }
    var hwStroke by remember { mutableStateOf(prefs.getFloat(KeyboardPrefs.KEY_HW_STROKE_DP, 4.4f)) }
    var hwDelay by remember { mutableStateOf(prefs.getInt(KeyboardPrefs.KEY_HW_AUTOCOMMIT_MS, 800).toFloat()) }

    SectionTitle("手写面板定制（4万生僻字全覆盖）")
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
private fun ChineseSchemaSection(prefs: android.content.SharedPreferences) {
    var schema by remember { mutableStateOf(prefs.getString(ImeService.KEY_SCHEMA, "rime_ice")!!) }
    var handwritingSelected by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_HANDWRITING, false)) }
    var smartZh by remember { mutableStateOf(prefs.getBoolean(KeyboardPrefs.KEY_SMART_PINYIN_CORRECT, true)) }
    var autoEn by remember { mutableStateOf(prefs.getBoolean("auto_correct", true)) }
    var autoCaps by remember { mutableStateOf(prefs.getBoolean("auto_caps", true)) }
    var doubleSpace by remember { mutableStateOf(prefs.getBoolean("double_space", true)) }
    var trad by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_TRADITIONAL, false)) }

    val modeOptions = remember {
        buildList {
            RimeEngine.SCHEMAS.forEachIndexed { idx, pair ->
                add(pair)
                if (idx == 1) add("handwriting" to "手写输入（全字集生僻字 · 连写 / 叠写）")
            }
        }
    }

    SectionTitle("输入方案与智能纠错")
    CardBox {
        SwitchItem("中文智能纠错（邻键误触 & 倒序修复）", "例如误打 nihso 自动提示「你好」，zhognguo 自动提示「中国」", smartZh) {
            smartZh = it; prefs.edit().putBoolean(KeyboardPrefs.KEY_SMART_PINYIN_CORRECT, it).apply()
        }
        RowDivider()
        SwitchItem("英文智能纠错与漏空格切分", "自动修正邻键拼写及 thebest → the best", autoEn) {
            autoEn = it; prefs.edit().putBoolean("auto_correct", it).apply()
        }
        RowDivider()
        SwitchItem("英文句首自动大写", null, autoCaps) {
            autoCaps = it; prefs.edit().putBoolean("auto_caps", it).apply()
        }
        RowDivider()
        SwitchItem("双击空格输入句号", null, doubleSpace) {
            doubleSpace = it; prefs.edit().putBoolean("double_space", it).apply()
        }
        RowDivider()
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
private fun AiSection(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val userFile = remember { AiRanker.modelFile(context) }
    val hasBuiltin = remember { AiRanker.hasBuiltinModel(context) }
    var userSize by remember { mutableStateOf(if (userFile.exists()) userFile.length() else 0L) }
    var aiOn by remember { mutableStateOf(prefs.getBoolean(ImeService.KEY_AI, true)) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "正在导入外部模型…"
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
                result.onSuccess { userSize = it; status = "外部模型已导入并激活" }
                    .onFailure { status = "导入失败：${it.message}" }
            }
        }.start()
    }

    SectionTitle("端侧 AI 智能选句（上下文同音消歧）")
    CardBox {
        SwitchItem("启用本地大模型优化选词与整句", "根据前文语境智能判别同音词（如「秋水 + shizhi → 时至」）", aiOn) {
            aiOn = it; prefs.edit().putBoolean(ImeService.KEY_AI, it).apply()
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当前模型", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            val modelDesc = when {
                userSize > 0 -> "自定义模型 · ${userSize / 1_000_000} MB"
                hasBuiltin -> "内置旗舰 Lingxi-285M · 已就绪"
                else -> "未安装（请导入 .gguf）"
            }
            Text(modelDesc, fontSize = 14.5.sp, color = if (userSize > 0 || hasBuiltin) Color(0xFF16A34A) else Secondary(), fontWeight = FontWeight.Medium)
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clickable { picker.launch(arrayOf("*/*")) }.padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Text(
                if (userSize > 0 || hasBuiltin) "导入其他自定义模型（.gguf）…" else "导入模型文件（.gguf）…",
                fontSize = 16.sp,
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
                    if (hasBuiltin) "移除外部模型并恢复内置 Lingxi-285M" else "删除已导入模型",
                    fontSize = 16.sp,
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
    prefs: android.content.SharedPreferences,
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
private fun FuzzyPinyinSection(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var fuzzyOn by remember { mutableStateOf(prefs.getBoolean(RimeEngine.PREF_FUZZY_ENABLED, false)) }
    var selected by remember {
        mutableStateOf(prefs.getStringSet(RimeEngine.PREF_FUZZY_PAIRS, RimeEngine.COMMON_FUZZY_IDS)!!.toSet())
    }
    var expanded by remember { mutableStateOf(false) }
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

    SectionTitle("模糊拼音（12组声韵母）")
    CardBox {
        SwitchItem(
            title = "启用模糊拼音",
            subtitle = if (!fuzzyOn) null else if (rimeState == RimeEngine.State.DEPLOYING) "正在后台更新模糊音索引…" else "已启用 ${selected.size} 组",
            checked = fuzzyOn,
        ) { on ->
            val nextSet = if (on && selected.isEmpty()) RimeEngine.COMMON_FUZZY_IDS else selected
            applyFuzzy(on, nextSet)
            if (on) expanded = true
        }
        if (fuzzyOn) {
            RowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("展开选择模糊音组合", fontSize = 15.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起 ▲" else "展开 ▼", fontSize = 13.sp, color = Secondary())
            }
            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "常用南方模糊音预设",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clickable { applyFuzzy(true, RimeEngine.COMMON_FUZZY_IDS) },
                    )
                    Text(
                        "全选 12 组",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { applyFuzzy(true, RimeEngine.FUZZY_PAIRS.map { it.id }.toSet()) },
                    )
                }
                RimeEngine.FUZZY_PAIRS.forEach { pair ->
                    RowDivider()
                    val checked = pair.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (checked) selected - pair.id else selected + pair.id
                                applyFuzzy(true, next)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${pair.group} · ${pair.label}", fontSize = 15.5.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (checked) Text("✓", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    SectionTitle("离线隐私保障")
    CardBox {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    UserHistory(context.filesDir.resolve("en_user.tsv")).clear()
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
        "灵犀输入法在 AndroidManifest 中未声明 INTERNET 网络权限，操作系统内核层面阻断一切联网可能。您的所有输入、语音、手写、剪贴板与图片均 100% 留在手机本地。",
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
