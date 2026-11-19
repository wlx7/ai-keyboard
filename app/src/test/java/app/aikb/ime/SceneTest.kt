package app.aikb.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {

    @Test
    fun browserOmniboxAndSearchFilterEnableLearningUnlessIncognito() {
        val urlInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val urlScene = Scene.of(urlInfo)
        assertTrue("Browser URL/search bar must allow learning in normal mode", urlScene.learn)

        val incognitoUrlInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        assertFalse("Incognito flag must disable persistent disk learning", Scene.of(incognitoUrlInfo).learn)

        val filterInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        assertTrue("Search filter fields must allow learning", Scene.of(filterInfo).learn)

        val passwordInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        assertFalse("Password fields must never learn", Scene.of(passwordInfo).learn)
    }

    @Test
    fun geminiAndFlutterMultilineChatBoxesMapToTextNotPassword() {
        // Google Gemini / Compose / Flutter chat boxes often set TYPE_TEXT_FLAG_NO_SUGGESTIONS | MULTI_LINE
        // or Flutter's VISIBLE_PASSWORD | MULTI_LINE hack to hide English spellcheck underlines.
        val geminiChatInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val geminiScene = Scene.of(geminiChatInfo)
        assertTrue(geminiScene.kind == FieldKind.TEXT)
        assertTrue(geminiScene.learn)

        val flutterChatInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val flutterScene = Scene.of(flutterChatInfo)
        assertTrue("Multi-line VISIBLE_PASSWORD is a Flutter chat field, not a password", flutterScene.kind == FieldKind.TEXT)
        assertFalse("Single-line VISIBLE_PASSWORD remains a real password",
            Scene.of(EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }).kind == FieldKind.TEXT)
    }
}
