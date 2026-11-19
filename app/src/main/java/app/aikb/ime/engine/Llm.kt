package app.aikb.ime.engine

/** Binding to the llama.cpp candidate scorer (app/src/main/cpp/llm_jni.cpp). */
object Llm {
    init { System.loadLibrary("aikb_llm") }

    @JvmStatic external fun nativeLoad(path: String, threads: Int): Boolean
    @JvmStatic external fun nativeFree()
    /** Stops a running [nativeScore] early (it returns null). Safe from any thread. */
    @JvmStatic external fun nativeAbort()
    /** Debug: prefill timing for [nTokens] tokens with [outputs] logit rows; milliseconds. */
    @JvmStatic external fun nativeBench(nTokens: Int, outputs: Int): Double
    /** log P(candidate | context) for each candidate, or null on failure. */
    @JvmStatic external fun nativeScore(context: String, candidates: Array<String>): FloatArray?
    /** Generates up to [maxOut] context-aware CJK continuations given [context], sharing KV cache. */
    @JvmStatic external fun nativePredict(context: String, maxOut: Int): Array<String>?
}
