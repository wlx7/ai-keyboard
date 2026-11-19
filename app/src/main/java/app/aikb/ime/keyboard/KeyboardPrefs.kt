package app.aikb.ime.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized user personalization & ergonomic geometry preferences.
 * Read into an immutable snapshot for zero-allocation access during 120Hz Canvas drawing.
 */
data class KeyboardPrefs(
    val themeId: String = "system",
    val keyBorders: Boolean = true,
    val keyRadiusDp: Float = 7.5f,
    val heightScale: Float = 1.00f,
    val widthRatio: Float = 1.00f,
    val oneHandAlign: Int = 0, // -1 = left, 0 = center, 1 = right
    val bottomOffsetDp: Int = 0,
    val keyFontScale: Float = 1.00f,
    val candFontScale: Float = 1.00f,
    val numberRow: Boolean = false,
    val showKeyHints: Boolean = true,
    val keyPreview: Boolean = true,
    val longPressMs: Int = 270,
    val haptics: Boolean = true,
    val hapticStrengthMs: Int = 16,
    val sound: Boolean = false,
    val autoCorrect: Boolean = true,
    val smartPinyinCorrect: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val spaceSwipeCursor: Boolean = true,
    val swipeDeleteWord: Boolean = true,
    val screenshotBubble: Boolean = true,
    val hwHeightScale: Float = 1.24f,
    val hwStrokeWidthDp: Float = 4.4f,
    val hwAutoCommitMs: Int = 800,
) {
    companion object {
        const val KEY_THEME_ID = "theme_id"
        const val KEY_BORDERS = "key_borders"
        const val KEY_RADIUS_DP = "key_radius_dp"
        const val KEY_HEIGHT_SCALE = "kb_height_scale"
        const val KEY_WIDTH_RATIO = "kb_width_ratio"
        const val KEY_ONE_HAND_ALIGN = "kb_one_hand_align"
        const val KEY_BOTTOM_OFFSET_DP = "kb_bottom_offset_dp"
        const val KEY_FONT_SCALE = "key_font_scale"
        const val KEY_CAND_FONT_SCALE = "cand_font_scale"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_SHOW_HINTS = "show_key_hints"
        const val KEY_PREVIEW = "key_preview"
        const val KEY_LONG_PRESS_MS = "long_press_ms"
        const val KEY_HAPTIC_MS = "haptic_strength_ms"
        const val KEY_SMART_PINYIN_CORRECT = "smart_pinyin_correct"
        const val KEY_SPACE_SWIPE = "space_swipe_cursor"
        const val KEY_SWIPE_DELETE = "swipe_delete_word"
        const val KEY_SCREENSHOT_BUBBLE = "screenshot_bubble"
        const val KEY_HW_HEIGHT_SCALE = "hw_height_scale"
        const val KEY_HW_STROKE_DP = "hw_stroke_dp"
        const val KEY_HW_AUTOCOMMIT_MS = "hw_autocommit_ms"

        val APPEARANCE_KEYS = setOf(
            KEY_THEME_ID, KEY_BORDERS, KEY_RADIUS_DP, KEY_HEIGHT_SCALE, KEY_WIDTH_RATIO,
            KEY_ONE_HAND_ALIGN, KEY_BOTTOM_OFFSET_DP, KEY_FONT_SCALE, KEY_CAND_FONT_SCALE,
            KEY_NUMBER_ROW, KEY_SHOW_HINTS, KEY_PREVIEW, KEY_LONG_PRESS_MS, KEY_HAPTIC_MS,
            KEY_SMART_PINYIN_CORRECT, KEY_SPACE_SWIPE, KEY_SWIPE_DELETE, KEY_SCREENSHOT_BUBBLE,
            KEY_HW_HEIGHT_SCALE, KEY_HW_STROKE_DP, KEY_HW_AUTOCOMMIT_MS,
            "haptics", "sound", "auto_correct", "auto_caps", "double_space",
        )

        fun load(context: Context): KeyboardPrefs =
            from(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

        fun from(p: SharedPreferences): KeyboardPrefs = KeyboardPrefs(
            themeId = p.getString(KEY_THEME_ID, "system") ?: "system",
            keyBorders = p.getBoolean(KEY_BORDERS, true),
            keyRadiusDp = p.getFloat(KEY_RADIUS_DP, 7.5f).coerceIn(4f, 14f),
            heightScale = p.getFloat(KEY_HEIGHT_SCALE, 1.00f).coerceIn(0.75f, 1.35f),
            widthRatio = p.getFloat(KEY_WIDTH_RATIO, 1.00f).coerceIn(0.76f, 1.00f),
            oneHandAlign = p.getInt(KEY_ONE_HAND_ALIGN, 0).coerceIn(-1, 1),
            bottomOffsetDp = p.getInt(KEY_BOTTOM_OFFSET_DP, 0).coerceIn(0, 44),
            keyFontScale = p.getFloat(KEY_FONT_SCALE, 1.00f).coerceIn(0.85f, 1.25f),
            candFontScale = p.getFloat(KEY_CAND_FONT_SCALE, 1.00f).coerceIn(0.85f, 1.30f),
            numberRow = p.getBoolean(KEY_NUMBER_ROW, false),
            showKeyHints = p.getBoolean(KEY_SHOW_HINTS, true),
            keyPreview = p.getBoolean(KEY_PREVIEW, true),
            longPressMs = p.getInt(KEY_LONG_PRESS_MS, 270).coerceIn(150, 450),
            haptics = p.getBoolean("haptics", true),
            hapticStrengthMs = p.getInt(KEY_HAPTIC_MS, 16).coerceIn(0, 50),
            sound = p.getBoolean("sound", false),
            autoCorrect = p.getBoolean("auto_correct", true),
            smartPinyinCorrect = p.getBoolean(KEY_SMART_PINYIN_CORRECT, true),
            autoCaps = p.getBoolean("auto_caps", true),
            doubleSpacePeriod = p.getBoolean("double_space", true),
            spaceSwipeCursor = p.getBoolean(KEY_SPACE_SWIPE, true),
            swipeDeleteWord = p.getBoolean(KEY_SWIPE_DELETE, true),
            screenshotBubble = p.getBoolean(KEY_SCREENSHOT_BUBBLE, true),
            hwHeightScale = p.getFloat(KEY_HW_HEIGHT_SCALE, 1.24f).coerceIn(1.00f, 1.55f),
            hwStrokeWidthDp = p.getFloat(KEY_HW_STROKE_DP, 4.4f).coerceIn(2.5f, 7.5f),
            hwAutoCommitMs = p.getInt(KEY_HW_AUTOCOMMIT_MS, 800).coerceIn(450, 1600),
        )
    }
}
