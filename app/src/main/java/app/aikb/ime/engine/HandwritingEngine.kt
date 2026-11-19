package app.aikb.ime.engine

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-Repertoire Offline Handwriting Engine (`AIKBHW02` + `AIKBID01`):
 *   1. 16,384-class `StrokeTransformer` neural classifier (`assets/dict/hw.bin`, ~5.05 MiB):
 *      preserves 100% of `ochwpro`'s 7,356 human-handwriting weights plus 9,028 norm-calibrated
 *      rare character classes (covering 99.8% of the 8,105 Standard Table + BMP/Ext-A rare chars).
 *   2. Corpus-calibrated Bayesian frequency priors (`priorLogits`): biases #1 toward daily common
 *      characters while keeping rare lookalikes accessible in the candidate bar.
 *   3. 40,000-character Spatial IDS Component Composer (`assets/dict/hw_ids.bin`, ~85k keys):
 *      composes 2-part (`⿰/⿱/⿴`) and 3-part (`品/⿲/⿳`) component segments across the full
 *      41,448-character Unicode CJK + Extensions A–G dictionary (e.g. `砳`, `垚`, `犇`, `龘`,
 *      `曌`, `烎`, `嫑`, `䶮`, `𠮷`, `𨰻`, `齾`).
 *   4. DP multi-character stroke segmentation for continuous (`连写`) and overlapped (`叠写`) input.
 */
class HandwritingEngine private constructor(
    val labels: Array<String>,
    private val priorLogits: FloatArray,
    private val dModel: Int,
    private val nHead: Int,
    private val numLayers: Int,
    private val dFF: Int,
    private val maxPosLen: Int,
    private val clsToken: FloatArray,
    private val posEmbed: FloatArray,
    private val inProjW: FloatArray,
    private val inProjB: FloatArray,
    private val layers: Array<LayerWeights>,
    private val finalNormW: FloatArray,
    private val finalNormB: FloatArray,
    private val cls1W: FloatArray,
    private val cls1B: FloatArray,
    private val cls4W: FloatArray,
    private val cls4B: FloatArray,
    var idsComposer: IdsComposer? = null,
) {
    data class Point(val x: Float, val y: Float)
    data class Candidate(val text: String, val prob: Float)

    private class LayerWeights(
        val norm1W: FloatArray, val norm1B: FloatArray,
        val qkvW: FloatArray, val qkvB: FloatArray,
        val outW: FloatArray, val outB: FloatArray,
        val norm2W: FloatArray, val norm2B: FloatArray,
        val lin1W: FloatArray, val lin1B: FloatArray,
        val lin2W: FloatArray, val lin2B: FloatArray,
    )

    private class SegmentResult(
        val top: List<Candidate>,
        val bestLogProb: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
    ) {
        val cx: Float get() = (minX + maxX) * 0.5f
        val cy: Float get() = (minY + maxY) * 0.5f
    }

    private val segCache = HashMap<Int, SegmentResult>()
    private var cachedStrokesRef: List<List<Point>> = emptyList()

    /**
     * Recognizes a single character from [strokes] with Bayesian common-character priors applied.
     */
    fun classifySingle(strokes: List<List<Point>>, topK: Int = 16, preferChinese: Boolean = true): List<Candidate> {
        if (strokes.isEmpty()) return emptyList()
        val simplified = simplifyStrokes(strokes, MAX_POINTS_PER_STROKE)
        val feat = strokesToFeatures(simplified)
        val seqLen = feat.size / 5
        if (seqLen == 0) return emptyList()

        val probs = forwardProbs(feat, seqLen)
        val poolK = min(max(topK * 3, 48), labels.size)
        val topIdx = topIndices(probs, poolK)

        if (!preferChinese) {
            return topIdx.take(topK).map { Candidate(labels[it], probs[it]) }
        }

        val out = ArrayList<Candidate>(topK)
        for (idx in topIdx) {
            val ch = labels[idx]
            if (ch.isNotEmpty() && isCjk(ch.codePointAt(0))) {
                val p = if (ch.length == 1 && ch[0] in RARE_RADICALS) probs[idx] * 0.20f else probs[idx]
                out += Candidate(ch, p)
            }
        }
        out.sortByDescending { it.prob }
        if (out.size > topK) out.subList(topK, out.size).clear()
        if (out.size < topK) {
            val seen = out.mapTo(HashSet()) { it.text }
            for (idx in topIdx) {
                val ch = labels[idx]
                if (ch != "?" && seen.add(ch)) {
                    out += Candidate(ch, probs[idx])
                    if (out.size >= topK) break
                }
            }
        }
        return out
    }

    /**
     * Recognizes single characters (common + 40,000+ rare characters) and continuously written
     * multi-character words via DP stroke segmentation + Spatial IDS Component Composition.
     */
    @Synchronized
    fun recognize(strokes: List<List<Point>>, topK: Int = 28, preferChinese: Boolean = true): List<String> {
        if (strokes.isEmpty()) {
            segCache.clear()
            cachedStrokesRef = emptyList()
            return emptyList()
        }
        val commonPrefix = commonStrokePrefix(cachedStrokesRef, strokes)
        if (commonPrefix < cachedStrokesRef.size) {
            val iter = segCache.keys.iterator()
            while (iter.hasNext()) {
                val k = iter.next()
                val e = k and 0xFFFF
                if (e > commonPrefix) iter.remove()
            }
        }
        cachedStrokesRef = strokes.toList()

        val n = strokes.size
        val whole = evalSegment(strokes, 0, n, preferChinese, candK = max(topK, 24))
        if (n == 1) return whole.top.take(topK).map { it.text }

        // DP over stroke intervals [j, i)
        val dpScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val dpPrev = IntArray(n + 1)
        dpScore[0] = 0f

        for (i in 1..n) {
            val jStart = max(0, i - MAX_STROKES_PER_CHAR)
            for (j in jStart until i) {
                val seg = evalSegment(strokes, j, i, preferChinese)
                val transitionPenalty = if (j == 0) {
                    0f
                } else {
                    val prevSeg = evalSegment(strokes, dpPrev[j], j, preferChinese)
                    val prevW = max(prevSeg.maxX - prevSeg.minX, 1f)
                    val prevH = max(prevSeg.maxY - prevSeg.minY, 1f)
                    val gapX = seg.minX - prevSeg.maxX
                    when {
                        gapX > 0.05f * max(prevW, prevH) -> -0.18f
                        else -> -0.55f
                    }
                }
                val segW = seg.maxX - seg.minX
                val segH = seg.maxY - seg.minY
                val aspectPen = if (i - j >= 2 && segH > 12f && segW > 1.40f * segH) {
                    -2.8f * (segW / segH - 1.40f)
                } else 0f
                val total = dpScore[j] + seg.bestLogProb + aspectPen + transitionPenalty
                if (total > dpScore[i]) {
                    dpScore[i] = total
                    dpPrev[i] = j
                }
            }
        }

        val cuts = ArrayList<Int>()
        var cur = n
        while (cur > 0) {
            cuts += cur
            cur = dpPrev[cur]
        }
        cuts += 0
        cuts.reverse()

        // Spatial IDS component composition for 2-part / 3-part rare characters (40,000+ repertoire)
        val gW = max(whole.maxX - whole.minX, 1f)
        val gH = max(whole.maxY - whole.minY, 1f)
        val isCompactBox = gW <= 1.32f * gH
        val idsCands = if (preferChinese && idsComposer != null && gW <= 1.75f * gH) {
            composeIdsCandidates(strokes, n, gW, gH)
        } else emptyList()

        val results = LinkedHashSet<String>(topK + 8)
        if (cuts.size > 2) {
            val segs = (0 until cuts.size - 1).map { k -> evalSegment(strokes, cuts[k], cuts[k + 1], preferChinese).top }
            val primaryMulti = segs.joinToString("") { it.firstOrNull()?.text.orEmpty() }

            // If written inside a compact single-character square (e.g. 石+石 for 砳, 龙+龙 for 龖, 钅+乐 for 𨰻),
            // promote strong single-character neural or IDS matches ahead of the multi-char string!
            if (isCompactBox && idsCands.isNotEmpty()) {
                val topWhole = whole.top.firstOrNull()
                if (topWhole != null && topWhole.prob >= 0.45f) results += topWhole.text
                results += idsCands.first()
                if (primaryMulti.isNotEmpty()) results += primaryMulti
                for (ic in idsCands.drop(1).take(5)) results += ic
                for (wc in whole.top.take(10)) results += wc.text
            } else {
                if (primaryMulti.isNotEmpty()) results += primaryMulti
                if (segs.size >= 2) {
                    val a0 = segs[0].take(3)
                    val a1 = segs[1].take(3)
                    val tail = if (segs.size > 2) segs.drop(2).joinToString("") { it.firstOrNull()?.text.orEmpty() } else ""
                    for (c0 in a0) for (c1 in a1) {
                        results += c0.text + c1.text + tail
                        if (results.size >= max(6, topK / 2)) break
                    }
                }
                for (ic in idsCands.take(6)) results += ic
                for (c in whole.top.take(12)) results += c.text
            }
        } else {
            // Single character won: keep confident common character #1, interleave IDS & rare candidates!
            val topWhole = whole.top.firstOrNull()
            if (topWhole != null && topWhole.prob >= 0.30f) {
                results += topWhole.text
                whole.top.getOrNull(1)?.takeIf { it.prob >= 0.12f }?.let { results += it.text }
                for (ic in idsCands.take(6)) results += ic
            } else {
                for (ic in idsCands.take(4)) results += ic
            }
            for (c in whole.top) {
                results += c.text
                if (results.size >= topK) break
            }
            for (ic in idsCands) {
                if (results.size >= topK) break
                results += ic
            }
        }
        return results.take(topK)
    }

    /**
     * Queries [idsComposer] across plausible 2-segment and 3-segment stroke splits.
     */
    private fun composeIdsCandidates(strokes: List<List<Point>>, n: Int, gW: Float, gH: Float): List<String> {
        val ids = idsComposer ?: return emptyList()
        val scored = HashMap<String, Float>()

        // 1. Two-part spatial compositions (⿰ H, ⿱ V, ⿴/⿵/⿸/⿺ E)
        for (k in 1 until n) {
            if (k > MAX_STROKES_PER_CHAR || n - k > MAX_STROKES_PER_CHAR) continue
            val sA = evalSegment(strokes, 0, k, preferChinese = true)
            val sB = evalSegment(strokes, k, n, preferChinese = true)
            if (sA.bestLogProb < -5.0f || sB.bestLogProb < -5.0f) continue

            val dx = sB.cx - sA.cx
            val dy = sB.cy - sA.cy
            val rels = ArrayList<Char>(3)
            if (dx > 0.08f * gW && abs(dy) <= 1.35f * abs(dx)) rels += 'H'
            if (dy > 0.08f * gH && abs(dx) <= 1.35f * abs(dy)) rels += 'V'
            val overlapX = min(sA.maxX, sB.maxX) - max(sA.minX, sB.minX)
            val overlapY = min(sA.maxY, sB.maxY) - max(sA.minY, sB.minY)
            if (overlapX > 0f && overlapY > 0f) rels += 'E'
            if (rels.isEmpty()) continue

            val topA = sA.top.take(3)
            val topB = sB.top.take(3)
            for (ra in topA.indices) {
                val ca = topA[ra]
                val lpA = ln(max(ca.prob, 1e-6f)) - ra * 0.6f
                for (rb in topB.indices) {
                    val cb = topB[rb]
                    val jointScore = lpA + ln(max(cb.prob, 1e-6f)) - rb * 0.6f
                    for (rel in rels) {
                        val hits = ids.lookup2(rel, ca.text, cb.text)
                        for (hIdx in hits.indices) {
                            val ch = hits[hIdx]
                            val sc = jointScore - hIdx * 0.45f
                            val prev = scored[ch]
                            if (prev == null || sc > prev) scored[ch] = sc
                        }
                    }
                }
            }
        }

        // 2. Three-part spatial compositions (`品` triangle, `⿲` 3-col, `⿳` 3-row, nested 3-part)
        if (n >= 3) {
            val validK1 = (1 until min(n - 1, MAX_STROKES_PER_CHAR + 1))
                .map { k1 -> k1 to evalSegment(strokes, 0, k1, preferChinese = true) }
                .filter { it.second.bestLogProb > -4.0f }
                .sortedByDescending { it.second.bestLogProb }
                .take(4)

            for ((k1, sA) in validK1) {
                val k2End = min(n, k1 + MAX_STROKES_PER_CHAR + 1)
                for (k2 in (k1 + 1) until k2End) {
                    if (n - k2 > MAX_STROKES_PER_CHAR) continue
                    val sB = evalSegment(strokes, k1, k2, preferChinese = true)
                    val sC = evalSegment(strokes, k2, n, preferChinese = true)
                    if (sB.bestLogProb < -4.0f || sC.bestLogProb < -4.0f) continue
                    val baseSc = sA.bestLogProb + sB.bestLogProb + sC.bestLogProb + 0.8f
                    for (ca in sA.top.take(2)) {
                        for (cb in sB.top.take(2)) {
                            for (cc in sC.top.take(2)) {
                                val hits = ids.lookup3(ca.text, cb.text, cc.text)
                                for (hIdx in hits.indices) {
                                    val ch = hits[hIdx]
                                    val sc = baseSc - hIdx * 0.45f
                                    val prev = scored[ch]
                                    if (prev == null || sc > prev) scored[ch] = sc
                                }
                            }
                        }
                    }
                }
            }
        }

        return scored.entries.sortedByDescending { it.value }.map { it.key }
    }

    @Synchronized
    fun clearCache() {
        segCache.clear()
        cachedStrokesRef = emptyList()
    }

    private fun commonStrokePrefix(a: List<List<Point>>, b: List<List<Point>>): Int {
        val m = min(a.size, b.size)
        var i = 0
        while (i < m && a[i] === b[i]) i++
        return i
    }

    private fun evalSegment(
        strokes: List<List<Point>>,
        j: Int,
        i: Int,
        preferChinese: Boolean,
        candK: Int = 14,
    ): SegmentResult {
        val key = (j shl 16) or i
        segCache[key]?.let { if (it.top.size >= candK || candK <= 14) return it }
        val sub = strokes.subList(j, i)
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (s in sub) for (p in s) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
        val top = classifySingle(sub, topK = candK, preferChinese = preferChinese)
        val bestP = top.firstOrNull()?.prob ?: 1e-6f
        val res = SegmentResult(top, ln(max(bestP, 1e-8f)), minX, maxX, minY, maxY)
        segCache[key] = res
        return res
    }

    // --------------------------------------------------------- neural forward pass

    private fun forwardProbs(feat: FloatArray, seqLen: Int): FloatArray {
        val sLen = min(seqLen + 1, maxPosLen)
        val d = dModel
        val headDim = d / nHead
        val scale = 1f / sqrt(headDim.toFloat())

        val h = FloatArray(sLen * d)
        for (c in 0 until d) h[c] = clsToken[c] + posEmbed[c]
        for (t in 0 until sLen - 1) {
            val fOff = t * 5
            val f0 = feat[f0Idx(fOff, 0)]; val f1 = feat[fOff + 1]; val f2 = feat[fOff + 2]
            val f3 = feat[fOff + 3]; val f4 = feat[fOff + 4]
            val hOff = (t + 1) * d
            val pOff = (t + 1) * d
            var wOff = 0
            for (o in 0 until d) {
                h[hOff + o] = inProjB[o] + posEmbed[pOff + o] +
                    inProjW[wOff] * f0 + inProjW[wOff + 1] * f1 + inProjW[wOff + 2] * f2 +
                    inProjW[wOff + 3] * f3 + inProjW[wOff + 4] * f4
                wOff += 5
            }
        }

        val normed = FloatArray(sLen * d)
        val qkv = FloatArray(sLen * 3 * d)
        val attnOut = FloatArray(d)
        val scores = FloatArray(sLen)
        val ffnMid = FloatArray(dFF)

        for (lIdx in 0 until numLayers) {
            val lw = layers[lIdx]
            val isLast = lIdx == numLayers - 1

            for (s in 0 until sLen) {
                layerNormRow(h, s * d, lw.norm1W, lw.norm1B, normed, s * d, d)
            }

            for (s in 0 until sLen) {
                val uOff = s * d
                val outBase = s * 3 * d
                val startOut = if (isLast && s > 0) d else 0
                for (o in startOut until 3 * d) {
                    var acc = lw.qkvB[o]
                    val wRow = o * d
                    var k = 0
                    while (k + 4 <= d) {
                        acc += lw.qkvW[wRow + k] * normed[uOff + k] +
                            lw.qkvW[wRow + k + 1] * normed[uOff + k + 1] +
                            lw.qkvW[wRow + k + 2] * normed[uOff + k + 2] +
                            lw.qkvW[wRow + k + 3] * normed[uOff + k + 3]
                        k += 4
                    }
                    while (k < d) { acc += lw.qkvW[wRow + k] * normed[uOff + k]; k++ }
                    qkv[outBase + o] = acc
                }
            }

            val qRows = if (isLast) 1 else sLen
            for (qi in 0 until qRows) {
                attnOut.fill(0f)
                val qBase = qi * 3 * d
                for (hd in 0 until nHead) {
                    val hOff = hd * headDim
                    var mx = Float.NEGATIVE_INFINITY
                    for (ki in 0 until sLen) {
                        val kBase = ki * 3 * d + d + hOff
                        var dot = 0f
                        for (c in 0 until headDim) dot += qkv[qBase + hOff + c] * qkv[kBase + c]
                        val sc = dot * scale
                        scores[ki] = sc
                        if (sc > mx) mx = sc
                    }
                    var sum = 0f
                    for (ki in 0 until sLen) {
                        val e = exp(scores[ki] - mx)
                        scores[ki] = e
                        sum += e
                    }
                    val invSum = 1f / sum
                    for (ki in 0 until sLen) {
                        val w = scores[ki] * invSum
                        val vBase = ki * 3 * d + 2 * d + hOff
                        for (c in 0 until headDim) attnOut[hOff + c] += w * qkv[vBase + c]
                    }
                }
                val hRow = qi * d
                for (o in 0 until d) {
                    var acc = lw.outB[o]
                    val wRow = o * d
                    var k = 0
                    while (k + 4 <= d) {
                        acc += lw.outW[wRow + k] * attnOut[k] +
                            lw.outW[wRow + k + 1] * attnOut[k + 1] +
                            lw.outW[wRow + k + 2] * attnOut[k + 2] +
                            lw.outW[wRow + k + 3] * attnOut[k + 3]
                        k += 4
                    }
                    while (k < d) { acc += lw.outW[wRow + k] * attnOut[k]; k++ }
                    h[hRow + o] += acc
                }
            }

            for (s in 0 until qRows) {
                val hRow = s * d
                layerNormRow(h, hRow, lw.norm2W, lw.norm2B, normed, 0, d)
                for (o in 0 until dFF) {
                    var acc = lw.lin1B[o]
                    val wRow = o * d
                    var k = 0
                    while (k + 4 <= d) {
                        acc += lw.lin1W[wRow + k] * normed[k] +
                            lw.lin1W[wRow + k + 1] * normed[k + 1] +
                            lw.lin1W[wRow + k + 2] * normed[k + 2] +
                            lw.lin1W[wRow + k + 3] * normed[k + 3]
                        k += 4
                    }
                    while (k < d) { acc += lw.lin1W[wRow + k] * normed[k]; k++ }
                    ffnMid[o] = geluErf(acc)
                }
                for (o in 0 until d) {
                    var acc = lw.lin2B[o]
                    val wRow = o * dFF
                    var k = 0
                    while (k + 4 <= dFF) {
                        acc += lw.lin2W[wRow + k] * ffnMid[k] +
                            lw.lin2W[wRow + k + 1] * ffnMid[k + 1] +
                            lw.lin2W[wRow + k + 2] * ffnMid[k + 2] +
                            lw.lin2W[wRow + k + 3] * ffnMid[k + 3]
                        k += 4
                    }
                    while (k < dFF) { acc += lw.lin2W[wRow + k] * ffnMid[k]; k++ }
                    h[hRow + o] += acc
                }
            }
        }

        val clsNorm = FloatArray(d)
        layerNormRow(h, 0, finalNormW, finalNormB, clsNorm, 0, d)

        val dHalf = cls1B.size
        val hid = FloatArray(dHalf)
        for (o in 0 until dHalf) {
            var acc = cls1B[o]
            val wRow = o * d
            for (k in 0 until d) acc += cls1W[wRow + k] * clsNorm[k]
            hid[o] = geluErf(acc)
        }

        val nCls = labels.size
        val logits = FloatArray(nCls)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (o in 0 until nCls) {
            var acc = cls4B[o] + priorLogits[o]
            val wRow = o * dHalf
            var k = 0
            while (k + 4 <= dHalf) {
                acc += cls4W[wRow + k] * hid[k] +
                    cls4W[wRow + k + 1] * hid[k + 1] +
                    cls4W[wRow + k + 2] * hid[k + 2] +
                    cls4W[wRow + k + 3] * hid[k + 3]
                k += 4
            }
            while (k < dHalf) { acc += cls4W[wRow + k] * hid[k]; k++ }
            logits[o] = acc
            if (acc > maxLogit) maxLogit = acc
        }

        var sumExp = 0f
        for (o in 0 until nCls) {
            val e = exp(logits[o] - maxLogit)
            logits[o] = e
            sumExp += e
        }
        val inv = 1f / sumExp
        for (o in 0 until nCls) logits[o] *= inv
        return logits
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun f0Idx(base: Int, off: Int) = base + off

    private fun layerNormRow(src: FloatArray, srcOff: Int, gamma: FloatArray, beta: FloatArray, dst: FloatArray, dstOff: Int, d: Int) {
        var sum = 0f
        for (i in 0 until d) sum += src[srcOff + i]
        val mean = sum / d
        var sq = 0f
        for (i in 0 until d) {
            val diff = src[srcOff + i] - mean
            sq += diff * diff
        }
        val invStd = 1f / sqrt(sq / d + 1e-5f)
        for (i in 0 until d) {
            dst[dstOff + i] = (src[srcOff + i] - mean) * invStd * gamma[i] + beta[i]
        }
    }

    private fun geluErf(x: Float): Float = 0.5f * x * (1f + erfApprox(x * 0.70710677f))

    private fun erfApprox(x: Float): Float {
        val sign = if (x < 0f) -1f else 1f
        val ax = abs(x)
        val t = 1f / (1f + 0.3275911f * ax)
        val y = 1f - (((((1.0614054f * t - 1.453152f) * t) + 1.4214137f) * t - 0.28449672f) * t + 0.2548296f) * t * exp(-ax * ax)
        return sign * y
    }

    private fun topIndices(probs: FloatArray, k: Int): IntArray {
        val bestIdx = IntArray(k) { -1 }
        val bestVal = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in probs.indices) {
            val v = probs[i]
            if (v <= bestVal[k - 1]) continue
            var pos = k - 1
            while (pos > 0 && v > bestVal[pos - 1]) {
                bestVal[pos] = bestVal[pos - 1]
                bestIdx[pos] = bestIdx[pos - 1]
                pos--
            }
            bestVal[pos] = v
            bestIdx[pos] = i
        }
        return bestIdx.filter { it >= 0 }.toIntArray()
    }

    private fun simplifyStrokes(strokes: List<List<Point>>, maxPts: Int): List<List<Point>> =
        strokes.map { stroke ->
            val n = stroke.size
            if (n in 4..maxPts) stroke
            else if (n in 2..3) {
                val out = ArrayList<Point>(6)
                for (i in 0 until n - 1) {
                    val a = stroke[i]; val b = stroke[i + 1]
                    val steps = if (n == 2) 4 else 2
                    for (s in 0 until steps) {
                        val t = s.toFloat() / steps
                        out += Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                    }
                }
                out += stroke.last()
                out
            } else if (n > maxPts) {
                val step = (n - 1f) / (maxPts - 1f)
                List(maxPts) { k ->
                    val idx = if (k == maxPts - 1) n - 1 else (k * step).roundToInt().coerceIn(0, n - 1)
                    stroke[idx]
                }
            } else stroke
        }

    private fun strokesToFeatures(strokes: List<List<Point>>): FloatArray {
        var total = 0
        for (s in strokes) total += s.size
        total = min(total, maxPosLen - 1)
        if (total == 0) return FloatArray(0)

        val xs = FloatArray(total)
        val ys = FloatArray(total)
        val pen = FloatArray(total)
        var k = 0
        outer@ for (s in strokes) {
            if (s.isEmpty()) continue
            for (idx in s.indices) {
                if (k >= total) break@outer
                xs[k] = s[idx].x
                ys[k] = s[idx].y
                pen[k] = if (idx == s.lastIndex) 0f else 1f
                k++
            }
        }
        val n = k
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            if (xs[i] < minX) minX = xs[i]
            if (xs[i] > maxX) maxX = xs[i]
            if (ys[i] < minY) minY = ys[i]
            if (ys[i] > maxY) maxY = ys[i]
        }
        val spanX = maxX - minX
        val spanY = maxY - minY
        val minSpan = max(spanX, spanY) * 0.20f
        val rangeX = max(max(spanX, minSpan), 1f)
        val rangeY = max(max(spanY, minSpan), 1f)
        val out = FloatArray(n * 5)
        var prevX = xs[0]
        var prevY = ys[0]
        for (i in 0 until n) {
            val base = i * 5
            out[base] = (xs[i] - minX) / rangeX
            out[base + 1] = (ys[i] - minY) / rangeY
            out[base + 2] = (xs[i] - prevX) / rangeX
            out[base + 3] = (ys[i] - prevY) / rangeY
            out[base + 4] = pen[i]
            prevX = xs[i]
            prevY = ys[i]
        }
        return out
    }

    companion object {
        private const val MAX_POINTS_PER_STROKE = 8
        private const val MAX_STROKES_PER_CHAR = 32
        private const val RARE_RADICALS = "囗冖丶丨丿乂凵匚刂亻氵扌艹宀辶阝钅纟讠饣忄"

        @Volatile private var cachedInstance: HandwritingEngine? = null
        @Volatile private var s2tMap: Map<Char, Char>? = null

        fun toTraditional(context: Context, text: String): String {
            val map = s2tMap ?: synchronized(this) {
                s2tMap ?: runCatching {
                    val m = HashMap<Char, Char>(4096)
                    context.assets.open("rime/opencc/STCharacters.txt").bufferedReader().forEachLine { line ->
                        val tab = line.indexOf('\t')
                        if (tab == 1 && line.length >= 3) {
                            val src = line[0]
                            val dst = line.substring(tab + 1).trim().firstOrNull()
                            if (dst != null) m[src] = dst
                        }
                    }
                    m
                }.getOrDefault(emptyMap()).also { s2tMap = it }
            }
            if (map.isEmpty()) return text
            val sb = StringBuilder(text.length)
            for (ch in text) sb.append(map[ch] ?: ch)
            return sb.toString()
        }

        fun getOrLoad(context: Context): HandwritingEngine? {
            cachedInstance?.let { return it }
            return synchronized(this) {
                cachedInstance ?: runCatching {
                    val ids = IdsComposer.load(context)
                    context.assets.open("dict/hw.bin").use { fromStream(it, ids) }
                }.getOrNull()?.also { cachedInstance = it }
            }
        }

        fun unload() {
            cachedInstance = null
        }

        fun fromStream(raw: InputStream, idsComposer: IdsComposer? = null): HandwritingEngine {
            val ins = DataInputStream(BufferedInputStream(raw, 1 shl 16))
            val magic = ByteArray(8).also { ins.readFully(it) }.toString(Charsets.US_ASCII)
            require(magic == "AIKBHW01" || magic == "AIKBHW02") { "bad hw.bin magic: $magic" }
            val numClasses = ins.readInt()
            val dModel = ins.readInt()
            val nHead = ins.readInt()
            val numLayers = ins.readInt()
            val dFF = ins.readInt()
            val maxPosLen = ins.readInt()

            val labels = Array(numClasses) {
                val len = ins.readUnsignedByte()
                val b = ByteArray(len)
                ins.readFully(b)
                b.toString(Charsets.UTF_8)
            }

            val priorLogits = FloatArray(numClasses)
            if (magic == "AIKBHW02") {
                val pBytes = ByteArray(numClasses * 2)
                ins.readFully(pBytes)
                var bi = 0
                for (i in 0 until numClasses) {
                    val h = ((pBytes[bi].toInt() and 0xFF) shl 8) or (pBytes[bi + 1].toInt() and 0xFF)
                    priorLogits[i] = halfToFloat(h)
                    bi += 2
                }
            }

            fun readFp16Tensor(): FloatArray {
                val count = ins.readInt()
                val bytes = ByteArray(count * 2)
                ins.readFully(bytes)
                val out = FloatArray(count)
                var bi = 0
                for (i in 0 until count) {
                    val h = ((bytes[bi].toInt() and 0xFF) shl 8) or (bytes[bi + 1].toInt() and 0xFF)
                    out[i] = halfToFloat(h)
                    bi += 2
                }
                return out
            }

            val clsToken = readFp16Tensor()
            val posEmbed = readFp16Tensor()
            val inProjW = readFp16Tensor()
            val inProjB = readFp16Tensor()
            val layers = Array(numLayers) {
                LayerWeights(
                    norm1W = readFp16Tensor(), norm1B = readFp16Tensor(),
                    qkvW = readFp16Tensor(), qkvB = readFp16Tensor(),
                    outW = readFp16Tensor(), outB = readFp16Tensor(),
                    norm2W = readFp16Tensor(), norm2B = readFp16Tensor(),
                    lin1W = readFp16Tensor(), lin1B = readFp16Tensor(),
                    lin2W = readFp16Tensor(), lin2B = readFp16Tensor(),
                )
            }
            val normW = readFp16Tensor()
            val normB = readFp16Tensor()
            val cls1W = readFp16Tensor()
            val cls1B = readFp16Tensor()
            val cls4W = readFp16Tensor()
            val cls4B = readFp16Tensor()

            return HandwritingEngine(
                labels, priorLogits, dModel, nHead, numLayers, dFF, maxPosLen,
                clsToken, posEmbed, inProjW, inProjB, layers,
                normW, normB, cls1W, cls1B, cls4W, cls4B,
                idsComposer = idsComposer,
            )
        }

        private fun isCjk(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0x20000..0x323AF

        private fun halfToFloat(hbits: Int): Float {
            var mant = hbits and 0x03ff
            var exp = hbits and 0x7c00
            if (exp == 0x7c00) exp = 0x3fc00
            else if (exp != 0) {
                exp += 0x1c000
            } else if (mant != 0) {
                exp = 0x1c400
                do {
                    mant = mant shl 1
                    exp -= 0x400
                } while ((mant and 0x0400) == 0)
                mant = mant and 0x03ff
            }
            return Float.fromBits(((hbits and 0x8000) shl 16) or ((exp or mant) shl 13))
        }
    }
}
