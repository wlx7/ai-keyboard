package app.aikb.ime.keyboard

import android.content.Context
import android.content.res.Configuration

/**
 * Flagship keyboard color palettes with refined contrast, subtle micro-elevation shadows,
 * and optional borderless flat mode (`keyBorders = false`).
 */
data class KbTheme(
    val background: Int,
    val barBackground: Int,
    val key: Int,
    val keyPressed: Int,
    val funcKey: Int,
    val funcKeyPressed: Int,
    val keyShadow: Int,
    val text: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentText: Int,
    val popup: Int,
    val divider: Int,
    val candidateHighlight: Int,
    val isDark: Boolean = false,
) {
    fun withBorders(enabled: Boolean): KbTheme =
        if (enabled) this else copy(
            key = background,
            funcKey = background,
            keyShadow = 0x00000000,
        )

    companion object {
        data class Preset(val id: String, val name: String, val swatchBg: Int, val swatchKey: Int, val swatchAccent: Int)

        val PRESETS = listOf(
            Preset("system", "Keylo 自适应", 0xFFECEFF4.toInt(), 0xFFFFFFFF.toInt(), 0xFF2563EB.toInt()),
            Preset("pure_white", "凝脂纯白", 0xFFF3F5F9.toInt(), 0xFFFFFFFF.toInt(), 0xFF1677FF.toInt()),
            Preset("obsidian_dark", "曜石深黑", 0xFF121418.toInt(), 0xFF262A33.toInt(), 0xFF3B82F6.toInt()),
            Preset("morandi_blue", "莫兰迪雾蓝", 0xFFE3EAF2.toInt(), 0xFFFBFDFF.toInt(), 0xFF3A6EA5.toInt()),
            Preset("sakura_pink", "樱花落雪", 0xFFF9EBEF.toInt(), 0xFFFFFBFC.toInt(), 0xFFE15B84.toInt()),
            Preset("mint_green", "松石薄荷", 0xFFE4F1EC.toInt(), 0xFFFAFFFD.toInt(), 0xFF148F77.toInt()),
            Preset("warm_sand", "暖阳羊皮纸", 0xFFF3EFE6.toInt(), 0xFFFFFDF8.toInt(), 0xFFB86B27.toInt()),
            Preset("cyber_indigo", "极夜星辰", 0xFF0F111A.toInt(), 0xFF1E2233.toInt(), 0xFF7C5CFF.toInt()),
        )

        val LIGHT = KbTheme(
            background = 0xFFECEFF4.toInt(),
            barBackground = 0xFFE6EAF1.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFD8DEE8.toInt(),
            funcKey = 0xFFDCE1EA.toInt(),
            funcKeyPressed = 0xFFC7CFDC.toInt(),
            keyShadow = 0x240F172A,
            text = 0xFF111827.toInt(),
            textSecondary = 0xFF64748B.toInt(),
            accent = 0xFF2563EB.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            divider = 0x1A0F172A,
            candidateHighlight = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        val PURE_WHITE = KbTheme(
            background = 0xFFF3F5F9.toInt(),
            barBackground = 0xFFEEF1F6.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFE2E8F0.toInt(),
            funcKey = 0xFFE6EBF3.toInt(),
            funcKeyPressed = 0xFFD3DCE8.toInt(),
            keyShadow = 0x1E0F172A,
            text = 0xFF0F172A.toInt(),
            textSecondary = 0xFF64748B.toInt(),
            accent = 0xFF1677FF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            divider = 0x160F172A,
            candidateHighlight = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        val DARK = KbTheme(
            background = 0xFF14161B.toInt(),
            barBackground = 0xFF101216.toInt(),
            key = 0xFF282C35.toInt(),
            keyPressed = 0xFF3A404D.toInt(),
            funcKey = 0xFF1E2129.toInt(),
            funcKeyPressed = 0xFF323743.toInt(),
            keyShadow = 0x55000000,
            text = 0xFFF3F4F6.toInt(),
            textSecondary = 0xFF9CA3AF.toInt(),
            accent = 0xFF3B82F6.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFF353B47.toInt(),
            divider = 0x24FFFFFF,
            candidateHighlight = 0xFF2B313D.toInt(),
            isDark = true,
        )

        val MORANDI_BLUE = KbTheme(
            background = 0xFFE3EAF2.toInt(),
            barBackground = 0xFFDCE5EF.toInt(),
            key = 0xFFFBFDFF.toInt(),
            keyPressed = 0xFFCBD8E6.toInt(),
            funcKey = 0xFFD0DCE9.toInt(),
            funcKeyPressed = 0xFFBDCDE0.toInt(),
            keyShadow = 0x221E3A5F,
            text = 0xFF1A2B3C.toInt(),
            textSecondary = 0xFF5C738A.toInt(),
            accent = 0xFF3A6EA5.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            divider = 0x1C1A2B3C,
            candidateHighlight = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        val SAKURA_PINK = KbTheme(
            background = 0xFFF8EBF0.toInt(),
            barBackground = 0xFFF4E3EA.toInt(),
            key = 0xFFFFFBFC.toInt(),
            keyPressed = 0xFFF0D5DF.toInt(),
            funcKey = 0xFFF0DAE2.toInt(),
            funcKeyPressed = 0xFFE6C5D1.toInt(),
            keyShadow = 0x20681834,
            text = 0xFF381D28.toInt(),
            textSecondary = 0xFF8A6272.toInt(),
            accent = 0xFFE15B84.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFBFC.toInt(),
            divider = 0x1A381D28,
            candidateHighlight = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        val MINT_GREEN = KbTheme(
            background = 0xFFE4F1EC.toInt(),
            barBackground = 0xFFDCECE6.toInt(),
            key = 0xFFFAFFFD.toInt(),
            keyPressed = 0xFFCBE3DA.toInt(),
            funcKey = 0xFFCFE6DD.toInt(),
            funcKeyPressed = 0xFFBBD8CD.toInt(),
            keyShadow = 0x200E3F33,
            text = 0xFF14332B.toInt(),
            textSecondary = 0xFF567A70.toInt(),
            accent = 0xFF148F77.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFAFFFD.toInt(),
            divider = 0x1A14332B,
            candidateHighlight = 0xFFFFFFFF.toInt(),
            isDark = false,
        )

        val WARM_SAND = KbTheme(
            background = 0xFFF2EDE3.toInt(),
            barBackground = 0xFFECE6DA.toInt(),
            key = 0xFFFFFDF8.toInt(),
            keyPressed = 0xFFE2DACB.toInt(),
            funcKey = 0xFFE5DECFE.toInt().let { 0xFFE5DECF.toInt() },
            funcKeyPressed = 0xFFD5CCBA.toInt(),
            keyShadow = 0x22433018,
            text = 0xFF2D2419.toInt(),
            textSecondary = 0xFF7A6D5D.toInt(),
            accent = 0xFFB86B27.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFDF8.toInt(),
            divider = 0x1C2D2419,
            candidateHighlight = 0xFFFFFDF8.toInt(),
            isDark = false,
        )

        val CYBER_INDIGO = KbTheme(
            background = 0xFF0F111A.toInt(),
            barBackground = 0xFF0B0D14.toInt(),
            key = 0xFF1F2335.toInt(),
            keyPressed = 0xFF2E344F.toInt(),
            funcKey = 0xFF171A28.toInt(),
            funcKeyPressed = 0xFF272C42.toInt(),
            keyShadow = 0x66000000,
            text = 0xFFEEF2FF.toInt(),
            textSecondary = 0xFF8E98B8.toInt(),
            accent = 0xFF7C5CFF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFF292E45.toInt(),
            divider = 0x26FFFFFF,
            candidateHighlight = 0xFF252A40.toInt(),
            isDark = true,
        )

        fun resolve(context: Context, prefs: KeyboardPrefs): KbTheme {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val base = when (prefs.themeId) {
                "pure_white" -> PURE_WHITE
                "obsidian_dark" -> DARK
                "morandi_blue" -> MORANDI_BLUE
                "sakura_pink" -> SAKURA_PINK
                "mint_green" -> MINT_GREEN
                "warm_sand" -> WARM_SAND
                "cyber_indigo" -> CYBER_INDIGO
                else -> if (night) DARK else LIGHT
            }
            return base.withBorders(prefs.keyBorders)
        }

        fun of(context: Context): KbTheme = resolve(context, KeyboardPrefs.load(context))
    }
}
