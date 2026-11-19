package app.aikb.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import app.aikb.ime.keyboard.BottomRow
import app.aikb.ime.keyboard.Page

enum class FieldKind { TEXT, EMAIL, URL, PASSWORD, PERSON_NAME, NUMBER, PHONE }

/** What kind of field we are typing into, derived from EditorInfo. Drives layout and smartness. */
data class Scene(
    val kind: FieldKind,
    val action: Int,            // EditorInfo.IME_ACTION_* or IME_ACTION_NONE for newline
    val suggestions: Boolean,   // show candidates at all
    val autoCorrect: Boolean,
    val autoCaps: Boolean,
    val learn: Boolean,         // allowed to learn typed words (never in passwords/incognito)
    val decimal: Boolean = false,
    val signed: Boolean = false,
    val rawAction: Int = action,
    val multiLine: Boolean = false,
) {
    val bottomRow: BottomRow
        get() = when (kind) {
            FieldKind.EMAIL -> BottomRow.EMAIL
            FieldKind.URL -> BottomRow.URL
            else -> BottomRow.TEXT
        }

    val initialPage: Page
        get() = when (kind) {
            FieldKind.NUMBER -> Page.NUMBER
            FieldKind.PHONE -> Page.PHONE
            else -> Page.LETTERS
        }

    /** Passwords strictly disable Chinese; emails start in English by default; browser URL/omnibox allows Chinese search! */
    val forceEnglish: Boolean
        get() = kind == FieldKind.PASSWORD

    val preferEnglishOnStart: Boolean
        get() = kind == FieldKind.EMAIL || kind == FieldKind.PASSWORD

    /** Candidate taps append a space only in prose. */
    val spaceAfterPick: Boolean get() = kind == FieldKind.TEXT || kind == FieldKind.PERSON_NAME

    companion object {
        fun of(info: EditorInfo?): Scene {
            val type = info?.inputType ?: InputType.TYPE_CLASS_TEXT
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            val flags = type and InputType.TYPE_MASK_FLAGS
            val opts = info?.imeOptions ?: 0
            val multiLine = flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val noEnterAction = opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val rawAction = opts and EditorInfo.IME_MASK_ACTION
            var action = rawAction
            if (noEnterAction || (multiLine && action == EditorInfo.IME_ACTION_UNSPECIFIED)) action = EditorInfo.IME_ACTION_NONE
            if (action == EditorInfo.IME_ACTION_UNSPECIFIED) action = EditorInfo.IME_ACTION_NONE
            val incognito = opts and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

            return when (cls) {
                InputType.TYPE_CLASS_NUMBER -> Scene(
                    FieldKind.NUMBER, action, suggestions = false, autoCorrect = false, autoCaps = false, learn = false,
                    decimal = flags and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
                    signed = flags and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                    rawAction = rawAction,
                )
                InputType.TYPE_CLASS_PHONE ->
                    Scene(FieldKind.PHONE, action, false, false, false, false, rawAction = rawAction)
                InputType.TYPE_CLASS_DATETIME ->
                    Scene(FieldKind.NUMBER, action, false, false, false, false, decimal = true, rawAction = rawAction)
                else -> {
                    val noSuggest = flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
                    val caps = flags and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0
                    when (variation) {
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ->
                            Scene(FieldKind.EMAIL, action, true, false, false, !incognito, rawAction = rawAction)
                        InputType.TYPE_TEXT_VARIATION_URI ->
                            Scene(FieldKind.URL, action, true, false, false, false, rawAction = rawAction)
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ->
                            Scene(FieldKind.PASSWORD, action, false, false, false, false, rawAction = rawAction)
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME ->
                            Scene(FieldKind.PERSON_NAME, action, !noSuggest, false, true, !incognito, rawAction = rawAction)
                        InputType.TYPE_TEXT_VARIATION_FILTER ->
                            Scene(FieldKind.TEXT, action, !noSuggest, false, false, false, rawAction = rawAction, multiLine = multiLine)
                        else -> Scene(
                            FieldKind.TEXT, action, !noSuggest, autoCorrect = !noSuggest,
                            autoCaps = caps, learn = !incognito, rawAction = rawAction, multiLine = multiLine,
                        )
                    }
                }
            }
        }
    }
}
