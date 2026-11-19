// On-device language-model scorer (llama.cpp) for re-ranking IME candidates.
//
// score(context, candidates) returns log P(candidate | context) for each candidate:
//   * the context is decoded once into sequence 0 and its KV cache is reused across calls
//     (typing more pinyin keeps the same context, so usually nothing is re-decoded);
//   * every candidate branches off the context into its own sequence and all candidates are
//     decoded in ONE batch, so the cost is roughly (total candidate tokens), not N passes.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define LOG_TAG "AikbLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static double nowMs() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

namespace {

constexpr int kMaxSeq = 32;     // seq 0 = context, 1..31 = candidates
constexpr int kCtx = 512;

std::mutex g_lock;
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
const llama_vocab* g_vocab = nullptr;
std::vector<llama_token> g_cached;     // tokens currently in seq 0
std::vector<float> g_lastLogProbs;     // log-softmax after the last context token
std::vector<uint8_t> g_tokCjkLen;      // 1..6 if token is pure BMP CJK (U+4E00..U+9FFF), else 0
std::vector<uint8_t> g_tokIsParticle;  // 1 if token is a bare Chinese suffix particle
std::vector<std::string> g_tokPiece;   // UTF-8 piece for pure-CJK tokens
std::atomic<bool> g_abort{false};      // set from the UI thread when the user keeps typing

bool abortCallback(void*) { return g_abort.load(); }

void initVocabTables() {
    const int nVocab = llama_vocab_n_tokens(g_vocab);
    g_tokCjkLen.assign(nVocab, 0);
    g_tokIsParticle.assign(nVocab, 0);
    g_tokPiece.assign(nVocab, std::string());
    char buf[32];
    for (int t = 0; t < nVocab; ++t) {
        if (llama_vocab_is_control(g_vocab, t) || llama_vocab_is_eog(g_vocab, t)) continue;
        int n = llama_token_to_piece(g_vocab, t, buf, sizeof(buf), 0, false);
        if (n < 3 || n > 18 || (n % 3) != 0) continue;
        bool allCjk = true;
        for (int i = 0; i < n; i += 3) {
            uint8_t b0 = (uint8_t) buf[i], b1 = (uint8_t) buf[i + 1], b2 = (uint8_t) buf[i + 2];
            if ((b0 & 0xF0) != 0xE0 || (b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80) { allCjk = false; break; }
            uint32_t cp = ((uint32_t)(b0 & 0x0F) << 12) | ((uint32_t)(b1 & 0x3F) << 6) | (uint32_t)(b2 & 0x3F);
            if (cp < 0x4E00 || cp > 0x9FFF) { allCjk = false; break; }
        }
        if (!allCjk) continue;
        g_tokCjkLen[t] = (uint8_t) (n / 3);
        std::string s(buf, n);
        if (s == "的" || s == "了" || s == "吗" || s == "呢" || s == "吧" ||
            s == "啊" || s == "着" || s == "过" || s == "地" || s == "得") {
            g_tokIsParticle[t] = 1;
        }
        g_tokPiece[t] = std::move(s);
    }
}

jstring toJava(JNIEnv* env, const std::string& utf8) {
    std::vector<jchar> u16;
    u16.reserve(utf8.size());
    size_t i = 0;
    while (i < utf8.size()) {
        uint8_t b0 = (uint8_t) utf8[i];
        uint32_t cp = 0;
        if (b0 < 0x80) { cp = b0; i += 1; }
        else if ((b0 & 0xE0) == 0xC0 && i + 1 < utf8.size()) {
            cp = ((uint32_t)(b0 & 0x1F) << 6) | ((uint32_t) utf8[i + 1] & 0x3F); i += 2;
        } else if ((b0 & 0xF0) == 0xE0 && i + 2 < utf8.size()) {
            cp = ((uint32_t)(b0 & 0x0F) << 12) | (((uint32_t) utf8[i + 1] & 0x3F) << 6) | ((uint32_t) utf8[i + 2] & 0x3F); i += 3;
        } else if ((b0 & 0xF8) == 0xF0 && i + 3 < utf8.size()) {
            cp = ((uint32_t)(b0 & 0x07) << 18) | (((uint32_t) utf8[i + 1] & 0x3F) << 12) |
                 (((uint32_t) utf8[i + 2] & 0x3F) << 6) | ((uint32_t) utf8[i + 3] & 0x3F); i += 4;
        } else { i += 1; continue; }
        if (cp <= 0xFFFF) {
            u16.push_back((jchar) cp);
        } else {
            cp -= 0x10000;
            u16.push_back((jchar) (0xD800 + (cp >> 10)));
            u16.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(u16.data(), (jsize) u16.size());
}

std::vector<llama_token> tokenize(const std::string& s) {
    std::vector<llama_token> out(s.size() + 8);
    int n = llama_tokenize(g_vocab, s.data(), (int32_t) s.size(), out.data(), (int32_t) out.size(), false, false);
    if (n < 0) {
        out.resize(-n);
        n = llama_tokenize(g_vocab, s.data(), (int32_t) s.size(), out.data(), (int32_t) out.size(), false, false);
    }
    out.resize(std::max(n, 0));
    return out;
}

float logSumExp(const float* logits, int n);

void logSoftmax(const float* logits, int n, std::vector<float>& out) {
    out.resize(n);
    const float lse = logSumExp(logits, n);
    for (int i = 0; i < n; ++i) out[i] = logits[i] - lse;
}

/** log-sum-exp over the vocabulary; float accumulators in 8 lanes so the compiler vectorises it. */
float logSumExp(const float* logits, int n) {
    const float mx = *std::max_element(logits, logits + n);
    float acc[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    int i = 0;
    for (; i + 8 <= n; i += 8)
        for (int k = 0; k < 8; ++k) acc[k] += expf(logits[i + k] - mx);
    float sum = 0;
    for (; i < n; ++i) sum += expf(logits[i] - mx);
    for (float a : acc) sum += a;
    return mx + logf(sum);
}

float logProbOf(const float* logits, int n, llama_token t) { return logits[t] - logSumExp(logits, n); }

/** Makes seq 0 hold exactly [ctx] and refreshes g_lastLogProbs. */
bool syncContext(const std::vector<llama_token>& ctx) {
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t common = 0;
    while (common < ctx.size() && common < g_cached.size() && ctx[common] == g_cached[common]) ++common;
    if (common == ctx.size() && common == g_cached.size() && !g_lastLogProbs.empty()) return true; // unchanged
    if (common == ctx.size()) --common; // need the last token's logits again
    llama_memory_seq_rm(mem, 0, (llama_pos) common, -1);
    g_cached.resize(common);

    const int nVocab = llama_vocab_n_tokens(g_vocab);
    llama_batch batch = llama_batch_init(kCtx, 0, 1);
    for (size_t start = common; start < ctx.size(); start += kCtx) {
        size_t end = std::min(ctx.size(), start + kCtx);
        batch.n_tokens = 0;
        for (size_t i = start; i < end; ++i) {
            int k = batch.n_tokens++;
            batch.token[k] = ctx[i];
            batch.pos[k] = (llama_pos) i;
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = 0;
            batch.logits[k] = (i + 1 == ctx.size());
        }
        if (llama_decode(g_ctx, batch) != 0) { llama_batch_free(batch); g_cached.clear(); g_lastLogProbs.clear(); return false; }
        g_cached.insert(g_cached.end(), ctx.begin() + start, ctx.begin() + end);
    }
    logSoftmax(llama_get_logits_ith(g_ctx, batch.n_tokens - 1), nVocab, g_lastLogProbs);
    llama_batch_free(batch);
    return true;
}

std::string fromJava(JNIEnv* env, jstring s) {
    if (!s) return {};
    // GetStringUTFChars yields modified UTF-8; candidate text can contain emoji, so convert manually.
    const jchar* chars = env->GetStringChars(s, nullptr);
    jsize len = env->GetStringLength(s);
    std::string out;
    for (jsize i = 0; i < len; ++i) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00); ++i;
        }
        if (cp < 0x80) out += (char) cp;
        else if (cp < 0x800) { out += (char) (0xC0 | (cp >> 6)); out += (char) (0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += (char) (0xE0 | (cp >> 12)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
        else { out += (char) (0xF0 | (cp >> 18)); out += (char) (0x80 | ((cp >> 12) & 0x3F)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_aikb_ime_engine_Llm_##name

JNI_FN(jboolean, nativeLoad)(JNIEnv* env, jobject, jstring path, jint threads) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_ctx) return JNI_TRUE;
    static bool backend = false;
    if (!backend) { llama_backend_init(); backend = true; }
    std::string p = fromJava(env, path);
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // weights stay in the page cache, not on the native heap
    g_model = llama_model_load_from_file(p.c_str(), mp);
    if (!g_model) { LOGE("model load failed: %s", p.c_str()); return JNI_FALSE; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kCtx;
    cp.n_batch = kCtx;
    cp.n_ubatch = kCtx;
    cp.n_seq_max = kMaxSeq;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.kv_unified = true;  // candidates share the context's KV cells (seq_cp is then free)
    cp.no_perf = true;
    cp.abort_callback = abortCallback; // lets a new keystroke stop a running evaluation
    cp.abort_callback_data = nullptr;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("context init failed"); llama_model_free(g_model); g_model = nullptr; return JNI_FALSE; }
    g_vocab = llama_model_get_vocab(g_model);
    initVocabTables();
    g_cached.clear(); g_lastLogProbs.clear();
    LOGI("model loaded (%d threads, vocab %d) %s", threads, llama_vocab_n_tokens(g_vocab), llama_print_system_info());
    return JNI_TRUE;
}

JNI_FN(void, nativeFree)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cached.clear(); g_lastLogProbs.clear();
    g_tokCjkLen.clear(); g_tokIsParticle.clear(); g_tokPiece.clear();
}

/** log P(candidate | context) per candidate; null on failure. */
JNI_FN(jfloatArray, nativeScore)(JNIEnv* env, jobject, jstring context, jobjectArray candidates) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!g_ctx) return nullptr;
    const int n = std::min<int>(env->GetArrayLength(candidates), kMaxSeq - 1);
    g_abort = false;
    // When context is empty, anchor with "。\n" (clean Chinese sentence boundary) instead of bare "\n",
    // eliminating Wikipedia/Baidu-Baike headword bias ("\n<RareWord>是指...") while preserving KV cache.
    std::string rawCtx = fromJava(env, context);
    std::vector<llama_token> ctxTok = tokenize(rawCtx.empty() ? "。\n" : ("\n" + rawCtx));
    if ((int) ctxTok.size() > kCtx - 64) ctxTok.erase(ctxTok.begin() + 1, ctxTok.end() - (kCtx - 64 - 1));

    std::vector<std::vector<llama_token>> cand(n);
    for (int i = 0; i < n; ++i) {
        auto js = (jstring) env->GetObjectArrayElement(candidates, i);
        cand[i] = tokenize(fromJava(env, js));
        env->DeleteLocalRef(js);
    }
    // Tokens shared by every candidate score identically in all of them, so they cannot change
    // the ranking: move them into the (cached) context and score only the differing tails.
    // Sentence alternatives usually differ in a few characters, so this saves most of the work
    // (the output layer over a 152k vocabulary dominates the cost per scored position).
    size_t lcp = 0;
    if (n > 1) {
        size_t minLen = cand[0].size();
        for (auto& c : cand) minLen = std::min(minLen, c.size());
        while (lcp + 1 < minLen) { // keep >= 1 token per candidate
            bool same = true;
            for (int i = 1; i < n && same; ++i) same = cand[i][lcp] == cand[0][lcp];
            if (!same) break;
            ++lcp;
        }
    }
    if (lcp > 0) {
        ctxTok.insert(ctxTok.end(), cand[0].begin(), cand[0].begin() + lcp);
        for (auto& c : cand) c.erase(c.begin(), c.begin() + lcp);
    }
    // Likewise a shared ending: beyond a couple of tokens after the last difference it barely
    // moves the comparison but costs a full forward pass per token. Keep kTail tokens of it so
    // local agreement ("…会议改到" vs "…回忆改到") is still judged.
    constexpr size_t kTail = 2;
    if (n > 1) {
        size_t minLen = cand[0].size();
        for (auto& c : cand) minLen = std::min(minLen, c.size());
        size_t lcs = 0;
        while (lcs + 1 < minLen) {
            bool same = true;
            for (int i = 1; i < n && same; ++i)
                same = cand[i][cand[i].size() - 1 - lcs] == cand[0][cand[0].size() - 1 - lcs];
            if (!same) break;
            ++lcs;
        }
        if (lcs > kTail) for (auto& c : cand) c.resize(c.size() - (lcs - kTail));
    }
    const double tA = nowMs();
    const size_t cachedBefore = g_cached.size();
    if (!syncContext(ctxTok)) return nullptr;
    const double tB = nowMs();
    int total = 0;
    for (auto& c : cand) {
        if ((int) (ctxTok.size() + c.size()) >= kCtx) c.clear();
        total += (int) c.size();
    }
    if (total > kCtx) return nullptr;

    llama_memory_t mem = llama_get_memory(g_ctx);
    const llama_pos base = (llama_pos) ctxTok.size();
    llama_batch batch = llama_batch_init(std::max(total, 1), 0, 1);
    std::vector<std::vector<int>> idx(n); // batch index of each candidate token
    for (int i = 0; i < n; ++i) {
        if (cand[i].empty()) continue;
        llama_memory_seq_cp(mem, 0, i + 1, 0, base);
        for (size_t j = 0; j < cand[i].size(); ++j) {
            int k = batch.n_tokens++;
            batch.token[k] = cand[i][j];
            batch.pos[k] = base + (llama_pos) j;
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = i + 1;
            batch.logits[k] = (j + 1 < cand[i].size()); // logits at j predict token j+1
            idx[i].push_back(k);
        }
    }
    std::vector<float> scores(n, -1e9f);
    bool ok = batch.n_tokens == 0 || llama_decode(g_ctx, batch) == 0;
    const double tC = nowMs();
    int outputs = 0;
    for (int k = 0; k < batch.n_tokens; ++k) outputs += batch.logits[k];
    if (ok) {
        const int nVocab = llama_vocab_n_tokens(g_vocab);
        for (int i = 0; i < n; ++i) {
            if (cand[i].empty()) continue;
            float lp = g_lastLogProbs[cand[i][0]];
            for (size_t j = 1; j < cand[i].size(); ++j)
                lp += logProbOf(llama_get_logits_ith(g_ctx, idx[i][j - 1]), nVocab, cand[i][j]);
            scores[i] = lp;
        }
    }
    llama_batch_free(batch);
    for (int i = 0; i < n; ++i) llama_memory_seq_rm(mem, i + 1, -1, -1);
    if (!ok) return nullptr;
    LOGI("score: ctx %zu tok (%zu cached) %.0f ms | cands %d, %d tok, %d outputs %.0f ms | logprob %.0f ms",
         ctxTok.size(), cachedBefore, tB - tA, n, total, outputs, tC - tB, nowMs() - tC);
    jfloatArray out = env->NewFloatArray(n);
    env->SetFloatArrayRegion(out, 0, n, scores.data());
    return out;
}

/** Asks a running nativeScore/nativePredict to stop as soon as possible (it then returns null). Lock-free. */
JNI_FN(void, nativeAbort)(JNIEnv*, jobject) { g_abort = true; }

/**
 * Proposes up to [maxOut] next-word continuations for the full sentence [context] before the cursor.
 *
 * Only WHOLE multi-character vocabulary tokens (2..4 Hanzi) are returned: stitching a single character
 * onto the next token produced non-words ("夫在", "和马", "在哪个"). A strict confidence floor
 * (log P >= kPredictMinLp) keeps the model silent when it does not know (names, idioms, short tails),
 * so the dictionary answers instead. Reuses seq 0's cached logits: no extra decode, ~1 ms after sync.
 */
JNI_FN(jobjectArray, nativePredict)(JNIEnv* env, jobject, jstring context, jint maxOut) {
    constexpr float kPredictMinLp = -3.3f;
    std::lock_guard<std::mutex> lock(g_lock);
    if (!g_ctx || maxOut <= 0 || g_tokCjkLen.empty()) return nullptr;
    g_abort = false;
    std::string rawCtx = fromJava(env, context);
    if (rawCtx.empty()) return nullptr;

    const double t0 = nowMs();
    std::vector<llama_token> ctxTok = tokenize("\n" + rawCtx);
    if ((int) ctxTok.size() > kCtx - 16) ctxTok.erase(ctxTok.begin() + 1, ctxTok.end() - (kCtx - 16 - 1));
    if (!syncContext(ctxTok) || g_abort.load() || g_lastLogProbs.empty()) return nullptr;

    struct TokScore { llama_token tok; float lp; };
    std::vector<TokScore> top;
    const int nVocab = (int) g_tokCjkLen.size();
    const int cap = std::min<int>(maxOut, 6);
    for (int t = 0; t < nVocab; ++t) {
        const uint8_t clen = g_tokCjkLen[t];
        if (clen < 2 || clen > 4) continue;
        const float lp = g_lastLogProbs[t];
        if (lp < kPredictMinLp) continue;
        top.push_back({t, lp});
    }
    if (top.empty()) return nullptr;
    std::sort(top.begin(), top.end(), [](const TokScore& a, const TokScore& b) { return a.lp > b.lp; });
    if ((int) top.size() > cap) top.resize(cap);

    LOGI("predict: ctx %zu tok -> %zu words (best %.2f) in %.1f ms", ctxTok.size(), top.size(), top[0].lp, nowMs() - t0);
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize) top.size(), strCls, nullptr);
    for (size_t i = 0; i < top.size(); ++i) {
        jstring js = toJava(env, g_tokPiece[top[i].tok]);
        env->SetObjectArrayElement(arr, (jsize) i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

/** Debug: prefill [nTokens] dummy tokens in a scratch sequence; returns milliseconds. */
JNI_FN(jdouble, nativeBench)(JNIEnv*, jobject, jint nTokens, jint outputs) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!g_ctx) return -1;
    llama_memory_t mem = llama_get_memory(g_ctx);
    const int seq = kMaxSeq - 1;
    llama_memory_seq_rm(mem, seq, -1, -1);
    llama_batch b = llama_batch_init(nTokens, 0, 1);
    for (int i = 0; i < nTokens; ++i) {
        b.token[i] = 1000 + i; b.pos[i] = i; b.n_seq_id[i] = 1; b.seq_id[i][0] = seq;
        b.logits[i] = i >= nTokens - outputs;
    }
    b.n_tokens = nTokens;
    double t0 = nowMs();
    llama_decode(g_ctx, b);
    double ms = nowMs() - t0;
    llama_batch_free(b);
    llama_memory_seq_rm(mem, seq, -1, -1);
    return ms;
}
