package app.aikb.ime.engine

/** Thin binding to librime (see app/src/main/cpp/rime_jni.cpp). */
object Rime {
    init { System.loadLibrary("aikb_rime") }

    // X11 keysyms used by librime
    const val XK_BACKSPACE = 0xff08
    const val XK_RETURN = 0xff0d
    const val XK_ESCAPE = 0xff1b
    const val XK_SPACE = 0x20

    @JvmStatic external fun nativeStart(sharedDir: String, userDir: String, logDir: String, fullCheck: Boolean): Boolean
    @JvmStatic external fun nativeFinalize()
    @JvmStatic external fun nativeProcessKey(keycode: Int, mask: Int): Boolean
    @JvmStatic external fun nativeGetCommit(): String?
    @JvmStatic external fun nativeGetComposition(): Array<String?>
    @JvmStatic external fun nativeGetCandidates(max: Int): Array<String>
    @JvmStatic external fun nativeSelectCandidate(index: Int): Boolean
    @JvmStatic external fun nativeDeleteCandidate(index: Int): Boolean
    @JvmStatic external fun nativeClearComposition()
    @JvmStatic external fun nativeSetOption(name: String, value: Boolean)
    @JvmStatic external fun nativeGetOption(name: String): Boolean
    @JvmStatic external fun nativeSelectSchema(id: String): Boolean
    @JvmStatic external fun nativeCurrentSchema(): String?
    @JvmStatic external fun nativeSchemaList(): Array<String>
    @JvmStatic external fun nativeSyncUserData()
    /** Whole-sentence alternatives for [input] (librime order first), explored in a hidden session. */
    @JvmStatic external fun nativeAlternatives(schema: String, input: String, syllables: Int, firstK: Int, secondK: Int): Array<String>
}
