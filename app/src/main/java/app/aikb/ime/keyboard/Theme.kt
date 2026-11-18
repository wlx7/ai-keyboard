package app.aikb.ime.keyboard

import android.content.Context
import android.content.res.Configuration

/** iOS-like keyboard palette. Follows the system light/dark setting. */
data class KbTheme(
    val background: Int,
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
) {
    companion object {
        val LIGHT = KbTheme(
            background = 0xFFD1D4DA.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFB4B9C2.toInt(),
            funcKey = 0xFFACB2BC.toInt(),
            funcKeyPressed = 0xFFFFFFFF.toInt(),
            keyShadow = 0xFF8A8C90.toInt(),
            text = 0xFF000000.toInt(),
            textSecondary = 0xFF6D7079.toInt(),
            accent = 0xFF0A84FF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            divider = 0x33000000,
            candidateHighlight = 0xFFFFFFFF.toInt(),
        )
        val DARK = KbTheme(
            background = 0xFF1C1C1E.toInt(),
            key = 0xFF505052.toInt(),
            keyPressed = 0xFF2C2C2E.toInt(),
            funcKey = 0xFF2E2E30.toInt(),
            funcKeyPressed = 0xFF505052.toInt(),
            keyShadow = 0xFF000000.toInt(),
            text = 0xFFFFFFFF.toInt(),
            textSecondary = 0xFFA1A1A6.toInt(),
            accent = 0xFF0A84FF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFF6A6A6C.toInt(),
            divider = 0x33FFFFFF,
            candidateHighlight = 0xFF3A3A3C.toInt(),
        )

        fun of(context: Context): KbTheme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}
