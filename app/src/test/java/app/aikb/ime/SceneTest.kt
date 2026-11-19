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
}
