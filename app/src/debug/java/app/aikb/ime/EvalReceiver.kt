package app.aikb.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.aikb.ime.engine.AiRanker
import app.aikb.ime.engine.Llm
import app.aikb.ime.engine.RimeEngine

/**
 * Debug-only accuracy benchmark (assets/eval.tsv from tools/BuildEval.java).
 *
 *   adb shell am broadcast -n app.aikb.ime/.EvalReceiver [--ei limit N] [--ei threads T]
 *
 * Keep the app in the foreground while it runs: Android freezes background processes.
 * For each item the pinyin is typed into librime (rime_ice); top-1 of librime alone is compared
 * with AI re-ranking over a grid of prior weights (alpha) and confidence margins, applying the
 * same minimum-syllable rule as the keyboard. Results go to logcat tag "AikbEval".
 */
class EvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Return immediately: broadcasts must finish within ~10 s or the process is killed as an ANR.
        val app = context.applicationContext
        val limit = intent.getIntExtra("limit", Int.MAX_VALUE)
        val threads = intent.getIntExtra("threads", 0)
        Thread {
            try { run(app, limit, threads) } catch (t: Throwable) { Log.e(TAG, "eval failed", t) }
        }.start()
    }

    private fun run(app: Context, limit: Int, threads: Int) {
        RimeEngine.start(app, "rime_ice")
        while (RimeEngine.state == RimeEngine.State.DEPLOYING || RimeEngine.state == RimeEngine.State.IDLE) Thread.sleep(200)
        RimeEngine.selectSchema("rime_ice")
        val ai = AiRanker.ensureLoaded(app, threads)
        Log.i(TAG, "start: ai=$ai")
        if (ai) for ((t, o) in listOf(8 to 1, 32 to 1, 64 to 1, 64 to 32, 64 to 64)) {
            Llm.nativeBench(t, o) // warm-up
            Log.i(TAG, "bench: $t tokens, $o outputs: %.0f ms".format(Llm.nativeBench(t, o)))
        }
        val items = app.assets.open("eval.tsv").bufferedReader().readLines().map { it.split('\t') }.take(limit)
        val alphas = floatArrayOf(1f, 2f, 3f, 4f, 6f)
        val margins = floatArrayOf(0f, 0.5f, 1f, 2f, 3f)
        val hits = Array(alphas.size) { IntArray(margins.size) }
        var n = 0; var base = 0; var oracle = 0
        var msTotal = 0L; var calls = 0; var altTotal = 0; var exploreMs = 0L; var scoreMs = 0L
        val perCall = ArrayList<Long>()
        val diffs = ArrayList<String>()
        for (f in items) {
            val (ctx, py, target) = Triple(f[0], f[1], f[2])
            RimeEngine.clear()
            py.forEach { RimeEngine.processKey(it.code) }
            val snap = RimeEngine.snapshot(10)
            RimeEngine.clear()
            n++
            val baseTop = snap.candidates.firstOrNull()?.text
            if (baseTop == target) base++
            val syl = AiRanker.syllableCount(snap.preedit)
            val t0 = SystemClock.elapsedRealtime()
            val alts = if (ai && syl >= AiRanker.MIN_SYLLABLES) RimeEngine.alternatives("rime_ice", py, syl) else emptyList()
            val t1 = SystemClock.elapsedRealtime()
            val scored = if (alts.isNotEmpty()) AiRanker.scoreRaw(ctx, alts, syl) else null
            if (scored != null) { exploreMs += t1 - t0; scoreMs += SystemClock.elapsedRealtime() - t1; perCall += SystemClock.elapsedRealtime() - t0 }
            if (scored != null) { msTotal += SystemClock.elapsedRealtime() - t0; calls++; altTotal += alts.size }
            if ((alts.ifEmpty { listOfNotNull(baseTop) }).contains(target)) oracle++
            for (a in alphas.indices) for (m in margins.indices) {
                val top = scored?.let { alts[AiRanker.best(it, alts, alphas[a], useCache = false, m = margins[m])] } ?: baseTop
                if (top == target) hits[a][m]++
            }
            val aiTop = scored?.let { alts[AiRanker.best(it, alts, AiRanker.DEFAULT_ALPHA, useCache = false)] } ?: baseTop
            if (aiTop != baseTop) diffs += (if (aiTop == target) "fixed  " else if (baseTop == target) "BROKE  " else "other  ") +
                "$target | rime=$baseTop | ai=$aiTop"
        }
        fun pct(x: Int) = "%.1f%%".format(100.0 * x / n)
        Log.i(TAG, "items=$n  rime_top1=${pct(base)}  oracle=${pct(oracle)}")
        for (a in alphas.indices) Log.i(TAG, "alpha=${alphas[a]}  " +
            margins.indices.joinToString("  ") { "m=${margins[it]}:${pct(hits[a][it])}" })
        if (calls > 0) Log.i(TAG, "avg explore ${exploreMs / calls} ms + score ${scoreMs / calls} ms over $calls calls, avg alternatives ${"%.1f".format(altTotal.toFloat() / calls)}")
        if (perCall.isNotEmpty()) perCall.sort().also { Log.i(TAG, "per pause: median ${perCall[perCall.size / 2]} ms, p90 ${perCall[perCall.size * 9 / 10]} ms, max ${perCall.last()} ms") }
        diffs.forEach { Log.i(TAG, "diff: $it") }
        Log.i(TAG, "done")
    }

    companion object { private const val TAG = "AikbEval" }
}
