// JNI bridge between app.aikb.ime.engine.Rime (Kotlin) and librime.
// One process-wide engine with a single session; calls are serialised by a mutex.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "rime_api.h"

#define LOG_TAG "AikbRime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_lock;
// Set by user-driven calls so a background exploration yields the lock right away.
std::atomic<bool> g_abortExplore{false};
RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;
RimeSessionId g_explore = 0;
bool g_initialized = false;
std::string g_pendingCommit;

size_t utf8Len(const char* s) {
    size_t n = 0;
    for (; s && *s; ++s) if ((static_cast<unsigned char>(*s) & 0xC0) != 0x80) ++n;
    return n;
}

std::string trimSpaces(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\'')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\'')) --b;
    return s.substr(a, b - a);
}

// Proper UTF-8 -> UTF-16. NewStringUTF expects *modified* UTF-8 and aborts under CheckJNI on
// 4-byte sequences, which rime produces for emoji candidates.
jstring toJava(JNIEnv* env, const char* s) {
    if (!s) return nullptr;
    std::u16string out;
    const auto* p = reinterpret_cast<const unsigned char*>(s);
    while (*p) {
        uint32_t cp;
        int extra;
        if (*p < 0x80) { cp = *p; extra = 0; }
        else if ((*p >> 5) == 0x6) { cp = *p & 0x1F; extra = 1; }
        else if ((*p >> 4) == 0xE) { cp = *p & 0x0F; extra = 2; }
        else if ((*p >> 3) == 0x1E) { cp = *p & 0x07; extra = 3; }
        else { ++p; out.push_back(0xFFFD); continue; }
        ++p;
        bool ok = true;
        for (int i = 0; i < extra; ++i, ++p) {
            if ((*p >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | (*p & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()), static_cast<jsize>(out.size()));
}

std::string fromJava(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

jobjectArray stringArray(JNIEnv* env, const std::vector<const char*>& items) {
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(items.size()), cls, nullptr);
    for (size_t i = 0; i < items.size(); ++i) {
        jstring js = toJava(env, items[i] ? items[i] : "");
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

bool ensureSession() {
    if (!g_initialized) return false;
    if (g_session && g_api->find_session(g_session)) return true;
    g_session = g_api->create_session();
    return g_session != 0;
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_app_aikb_ime_engine_Rime_##name

/**
 * Sets up librime and runs maintenance (deploys schemas/dictionaries when they changed).
 * Blocking: call from a background thread. Returns true when a session is ready.
 */
JNI_FN(jboolean, nativeStart)(JNIEnv* env, jobject, jstring shared, jstring user, jstring logDir, jboolean fullCheck) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!g_api) g_api = rime_get_api();
    std::string sharedDir = fromJava(env, shared);
    std::string userDir = fromJava(env, user);
    std::string logs = fromJava(env, logDir);
    g_pendingCommit.clear();
    if (g_initialized) {
        if (g_session) { g_api->destroy_session(g_session); g_session = 0; }
        if (g_explore) { g_api->destroy_session(g_explore); g_explore = 0; }
        g_api->finalize();
        g_initialized = false;
    }
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = sharedDir.c_str();
    traits.user_data_dir = userDir.c_str();
    traits.distribution_name = "Lingxi";
    traits.distribution_code_name = "lingxi";
    traits.distribution_version = "0.2";
    traits.app_name = "rime.lingxi";
    traits.min_log_level = 2;   // errors only
    // Release builds pass "" (no log files at all); debug builds get errors-only logs in cache.
    traits.log_dir = logs.c_str();
    static bool setupDone = false;
    if (!setupDone) { g_api->setup(&traits); setupDone = true; }
    g_api->initialize(&traits);
    g_initialized = true;
    if (g_api->start_maintenance(fullCheck ? True : False)) g_api->join_maintenance_thread();
    bool ok = ensureSession();
    LOGI("rime %s started, session=%llu", g_api->get_version(), (unsigned long long) g_session);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeFinalize)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    g_pendingCommit.clear();
    if (!g_initialized) return;
    if (g_session) { g_api->destroy_session(g_session); g_session = 0; }
    if (g_explore) { g_api->destroy_session(g_explore); g_explore = 0; }
    g_api->finalize();
    g_initialized = false;
}

JNI_FN(jboolean, nativeProcessKey)(JNIEnv*, jobject, jint keycode, jint mask) {
    g_abortExplore = true;
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return JNI_FALSE;
    // Defensive guard: if BackSpace is pressed while a prefix segment was somehow left confirmed
    // inside Rime composition, never reopen the confirmed Hanzi back into raw Pinyin!
    if (keycode == 0xff08 && mask == 0) {
        RIME_STRUCT(RimeContext, ctx);
        if (g_api->get_context(g_session, &ctx)) {
            int selStart = ctx.composition.sel_start;
            int selEnd = ctx.composition.sel_end;
            std::string pre = ctx.composition.preedit ? ctx.composition.preedit : "";
            g_api->free_context(&ctx);
            if (selStart > 0 && selStart <= static_cast<int>(pre.size()) && selEnd <= selStart) {
                g_pendingCommit = trimSpaces(pre.substr(0, selStart));
                g_api->clear_composition(g_session);
                return JNI_TRUE;
            }
        }
    }
    return g_api->process_key(g_session, keycode, mask) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jstring, nativeGetCommit)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!g_pendingCommit.empty()) {
        jstring r = toJava(env, g_pendingCommit.c_str());
        g_pendingCommit.clear();
        return r;
    }
    if (!ensureSession()) return nullptr;
    RIME_STRUCT(RimeCommit, commit);
    if (!g_api->get_commit(g_session, &commit)) return nullptr;
    jstring r = toJava(env, commit.text);
    g_api->free_commit(&commit);
    return r;
}

/** [preedit, commitTextPreview, rawInput] — null entries when not composing. */
JNI_FN(jobjectArray, nativeGetComposition)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    std::vector<const char*> items{nullptr, nullptr, nullptr};
    if (!ensureSession()) return stringArray(env, items);
    RIME_STRUCT(RimeContext, ctx);
    if (!g_api->get_context(g_session, &ctx)) return stringArray(env, items);
    items[0] = ctx.composition.preedit;
    items[1] = ctx.commit_text_preview;
    items[2] = g_api->get_input(g_session);
    jobjectArray r = stringArray(env, items);
    g_api->free_context(&ctx);
    return r;
}

/** Up to [max] candidates as [text0, comment0, text1, comment1, ...]. */
JNI_FN(jobjectArray, nativeGetCandidates)(JNIEnv* env, jobject, jint max) {
    std::lock_guard<std::mutex> lock(g_lock);
    std::vector<std::string> buf;
    if (ensureSession()) {
        RimeCandidateListIterator it = {nullptr, 0, {nullptr, nullptr, nullptr}};
        if (g_api->candidate_list_from_index(g_session, &it, 0)) {
            while (static_cast<int>(buf.size() / 2) < max && g_api->candidate_list_next(&it)) {
                buf.emplace_back(it.candidate.text ? it.candidate.text : "");
                buf.emplace_back(it.candidate.comment ? it.candidate.comment : "");
            }
            g_api->candidate_list_end(&it);
        }
    }
    std::vector<const char*> items;
    items.reserve(buf.size());
    for (auto& s : buf) items.push_back(s.c_str());
    return stringArray(env, items);
}

JNI_FN(jboolean, nativeSelectCandidate)(JNIEnv*, jobject, jint index) {
    g_abortExplore = true;
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return JNI_FALSE;
    const char* rawBefore = g_api->get_input(g_session);
    std::string fullInput = rawBefore ? rawBefore : "";
    if (!g_api->select_candidate(g_session, static_cast<size_t>(index))) return JNI_FALSE;

    // 1) Did Rime finish the entire composition?
    RIME_STRUCT(RimeCommit, commit);
    if (g_api->get_commit(g_session, &commit)) {
        if (commit.text) g_pendingCommit = commit.text;
        g_api->free_commit(&commit);
        return JNI_TRUE;
    }

    // 2) Partial candidate selection (e.g. "qiushuishizhi" -> user tapped "秋水"):
    //    Immediately commit "秋水" and keep ONLY the remaining raw input ("shizhi") composing,
    //    so pressing Backspace NEVER reverts "秋水" back into raw pinyin "qiushui", AND the AI
    //    model immediately receives "秋水" as committed prefix context!
    RIME_STRUCT(RimeContext, ctx);
    if (!g_api->get_context(g_session, &ctx)) return JNI_TRUE;
    int selStart = ctx.composition.sel_start;
    std::string pre = ctx.composition.preedit ? ctx.composition.preedit : "";
    std::string fullPreview = ctx.commit_text_preview ? ctx.commit_text_preview : "";
    g_api->free_context(&ctx);

    if (selStart > 0 && selStart <= static_cast<int>(pre.size()) && fullInput.size() >= 2) {
        std::string confirmedPrefix = trimSpaces(pre.substr(0, selStart));
        std::string unconvPreedit = trimSpaces(pre.substr(selStart));
        std::string expectedTailPreview;
        if (fullPreview.size() > confirmedPrefix.size() &&
            fullPreview.compare(0, confirmedPrefix.size(), confirmedPrefix) == 0) {
            expectedTailPreview = fullPreview.substr(confirmedPrefix.size());
        }

        if (!confirmedPrefix.empty() && !unconvPreedit.empty()) {
            if (!g_explore || !g_api->find_session(g_explore)) g_explore = g_api->create_session();
            char sessSch[128] = {0}, expSch[128] = {0};
            if (g_api->get_current_schema(g_session, sessSch, sizeof(sessSch))) {
                if (!g_api->get_current_schema(g_explore, expSch, sizeof(expSch)) || std::string(sessSch) != expSch)
                    g_api->select_schema(g_explore, sessSch);
            }
            for (const char* opt : {"traditionalization", "zh_trad", "simplification"}) {
                g_api->set_option(g_explore, opt, g_api->get_option(g_session, opt));
            }

            size_t nonSpaceTail = 0;
            for (char ch : unconvPreedit) if (ch != ' ') ++nonSpaceTail;
            std::vector<size_t> candidatesCut;
            if (nonSpaceTail > 0 && nonSpaceTail < fullInput.size())
                candidatesCut.push_back(fullInput.size() - nonSpaceTail);
            size_t dpCut = utf8Len(confirmedPrefix.c_str()) * 2;
            if (dpCut > 0 && dpCut < fullInput.size())
                candidatesCut.push_back(dpCut);
            for (size_t k = 1; k < fullInput.size(); ++k) candidatesCut.push_back(k);

            size_t matchedCut = 0;
            for (size_t cut : candidatesCut) {
                if (cut == 0 || cut >= fullInput.size()) continue;
                std::string tail = fullInput.substr(cut);
                g_api->clear_composition(g_explore);
                if (!g_api->set_input(g_explore, tail.c_str())) {
                    for (unsigned char c : tail) g_api->process_key(g_explore, c, 0);
                }
                RIME_STRUCT(RimeContext, ectx);
                bool match = false;
                if (g_api->get_context(g_explore, &ectx)) {
                    std::string ePre = trimSpaces(ectx.composition.preedit ? ectx.composition.preedit : "");
                    std::string ePrev = ectx.commit_text_preview ? ectx.commit_text_preview : "";
                    if (ePre == unconvPreedit || (!expectedTailPreview.empty() && ePrev == expectedTailPreview)) {
                        match = true;
                    }
                    g_api->free_context(&ectx);
                }
                if (match) { matchedCut = cut; break; }
            }
            g_api->clear_composition(g_explore);
            RIME_STRUCT(RimeCommit, stale);
            if (g_api->get_commit(g_explore, &stale)) g_api->free_commit(&stale);

            if (matchedCut > 0 && matchedCut < fullInput.size()) {
                std::string rem = fullInput.substr(matchedCut);
                g_pendingCommit = confirmedPrefix;
                g_api->clear_composition(g_session);
                if (!g_api->set_input(g_session, rem.c_str())) {
                    for (unsigned char c : rem) g_api->process_key(g_session, c, 0);
                }
            }
        }
    }
    return JNI_TRUE;
}

JNI_FN(jboolean, nativeDeleteCandidate)(JNIEnv*, jobject, jint index) {
    g_abortExplore = true;
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return JNI_FALSE;
    return g_api->delete_candidate(g_session, static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(void, nativeClearComposition)(JNIEnv*, jobject) {
    g_abortExplore = true;
    std::lock_guard<std::mutex> lock(g_lock);
    g_pendingCommit.clear();
    if (ensureSession()) g_api->clear_composition(g_session);
}

JNI_FN(void, nativeReplaceInput)(JNIEnv* env, jobject, jstring input) {
    g_abortExplore = true;
    std::lock_guard<std::mutex> lock(g_lock);
    g_pendingCommit.clear();
    if (!ensureSession()) return;
    std::string rem = fromJava(env, input);
    g_api->clear_composition(g_session);
    if (!rem.empty()) {
        if (!g_api->set_input(g_session, rem.c_str())) {
            for (unsigned char c : rem) g_api->process_key(g_session, c, 0);
        }
    }
}

JNI_FN(void, nativeSetOption)(JNIEnv* env, jobject, jstring name, jboolean value) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (ensureSession()) g_api->set_option(g_session, fromJava(env, name).c_str(), value ? True : False);
}

JNI_FN(jboolean, nativeGetOption)(JNIEnv* env, jobject, jstring name) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return JNI_FALSE;
    return g_api->get_option(g_session, fromJava(env, name).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jboolean, nativeSelectSchema)(JNIEnv* env, jobject, jstring id) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return JNI_FALSE;
    return g_api->select_schema(g_session, fromJava(env, id).c_str()) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jstring, nativeCurrentSchema)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return nullptr;
    char buf[128] = {0};
    if (!g_api->get_current_schema(g_session, buf, sizeof(buf))) return nullptr;
    return toJava(env, buf);
}

/** Installed schemas as [id0, name0, id1, name1, ...]. */
JNI_FN(jobjectArray, nativeSchemaList)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    std::vector<const char*> items;
    RimeSchemaList list = {0, nullptr};
    if (g_initialized && g_api->get_schema_list(&list)) {
        for (size_t i = 0; i < list.size; ++i) {
            items.push_back(list.list[i].schema_id);
            items.push_back(list.list[i].name);
        }
        jobjectArray r = stringArray(env, items);
        g_api->free_schema_list(&list);
        return r;
    }
    return stringArray(env, items);
}

// --------------------------------------------------------------------------------------------
// Sentence alternatives for the AI re-ranker.
//
// librime offers one whole-sentence candidate; the rest are prefix words. To get more full
// sentences we explore in a separate, hidden session: for each of the first prefix words,
// select it and read commit_text_preview (= that word + librime's best conversion of the rest),
// then do the same one level deeper. BackSpace reopens a selection, so the input is fed once.
// Candidates that would complete the input are read directly and never selected: completing
// would commit, and a commit in this session would teach the user dictionary phrases the user
// never typed.

namespace {

std::vector<std::pair<std::string, size_t>> exploreCandidates(int k) {
    std::vector<std::pair<std::string, size_t>> out;
    RimeCandidateListIterator it = {nullptr, 0, {nullptr, nullptr, nullptr}};
    if (g_api->candidate_list_from_index(g_explore, &it, 0)) {
        while (static_cast<int>(out.size()) < k && g_api->candidate_list_next(&it))
            out.emplace_back(it.candidate.text ? it.candidate.text : "", utf8Len(it.candidate.text));
        g_api->candidate_list_end(&it);
    }
    return out;
}

std::string explorePreview() {
    RIME_STRUCT(RimeContext, ctx);
    std::string s;
    if (g_api->get_context(g_explore, &ctx)) {
        if (ctx.commit_text_preview) s = ctx.commit_text_preview;
        g_api->free_context(&ctx);
    }
    return s;
}

std::string exploreInput() {
    const char* in = g_api->get_input(g_explore);
    return in ? in : "";
}

void exploreFeed(const std::string& input) {
    g_api->clear_composition(g_explore);
    // One conversion for the whole input instead of one per replayed keystroke.
    if (!g_api->set_input(g_explore, input.c_str()))
        for (unsigned char c : input) g_api->process_key(g_explore, c, 0);
}

void exploreUndo(const std::string& input) {
    g_api->process_key(g_explore, 0xff08, 0); // BackSpace reopens the last selection
    if (exploreInput() != input) exploreFeed(input);
}
}  // namespace

JNI_FN(jobjectArray, nativeAlternatives)(JNIEnv* env, jobject, jstring schema, jstring input,
                                         jint syllables, jint firstK, jint secondK) {
    std::lock_guard<std::mutex> lock(g_lock);
    std::vector<std::string> out;
    if (g_initialized) {
        if (!g_explore || !g_api->find_session(g_explore)) g_explore = g_api->create_session();
        std::string sch = fromJava(env, schema);
        char cur[128] = {0};
        if (!g_api->get_current_schema(g_explore, cur, sizeof(cur)) || sch != cur)
            g_api->select_schema(g_explore, sch.c_str());
        // Only plain sentences are wanted here; skip the emoji and 简→繁 filters (and their cost).
        g_api->set_option(g_explore, "emoji", False);
        g_api->set_option(g_explore, "traditionalization", False);
        std::string in = fromJava(env, input);
        g_abortExplore = false;
        auto add = [&](const std::string& s) {
            if (!s.empty() && out.size() < 24 && std::find(out.begin(), out.end(), s) == out.end())
                out.push_back(s);
        };

        // Helper: re-establish spine state by feeding input and replaying [spineChoices]
        auto restoreSpine = [&](const std::vector<size_t>& spineChoices) -> bool {
            if (g_abortExplore) return false;
            exploreFeed(in);
            for (size_t idx : spineChoices) {
                if (g_abortExplore || !g_api->select_candidate(g_explore, idx)) return false;
            }
            return true;
        };

        exploreFeed(in);
        const std::string defaultSentence = explorePreview();
        add(defaultSentence);

        std::vector<size_t> spine;
        std::string prefixText;
        size_t covered = 0;
        const size_t totalSyl = static_cast<size_t>(std::max<jint>(syllables, 0));

        for (int step = 0; step < 6 && covered < totalSyl && !g_abortExplore && out.size() < 24; ++step) {
            const size_t rem = totalSyl - covered;
            const int branchLimit = (step == 0) ? std::max<int>(firstK, 8) : 4;
            const int directLimit = (rem <= 3) ? 14 : 8;
            auto cands = exploreCandidates(std::max(branchLimit * 3 + 4, directLimit + 4));
            if (cands.empty()) break;

            // Find spine choice (first partial/full word that fits remaining syllables, skipping index 0 full-sentence if step==0)
            int spineIdx = -1;
            for (size_t i = 0; i < cands.size(); ++i) {
                const auto& [txt, len] = cands[i];
                if (step == 0 && i == 0 && len == totalSyl && cands.size() > 1) continue;
                if (len > 0 && len <= rem) { spineIdx = static_cast<int>(i); break; }
            }

            // Explore alternative words at this segment position.
            // Note: librime orders menu candidates from longest input span (`end == input.size()`) down to
            // shorter prefix spans (`end < input.size()`). Once we encounter any candidate with `len < rem`,
            // Rime has moved to partial-prefix matches, so any later candidate whose Hanzi count happens to
            // equal `rem` via super-abbreviation (`jinrich` -> `纪念日` matching only `jinri`) does NOT cover
            // the full remaining input and MUST NOT be accepted as a direct full-span completion!
            int branched = 0;
            int directAdded = 0;
            bool seenPartialSpan = false;
            for (size_t i = 0; i < cands.size() && !g_abortExplore && out.size() < 20; ++i) {
                const auto& [txt, len] = cands[i];
                if (len > 0 && len < rem) seenPartialSpan = true;
                if (static_cast<int>(i) == spineIdx && !(cands[i].second == rem)) continue;
                if (len == 0 || len > rem) continue;
                if (len == rem) {
                    if (!seenPartialSpan && directAdded < directLimit) {
                        add(prefixText + txt);
                        ++directAdded;
                    }
                    continue;
                }
                if (branched >= branchLimit) continue;
                ++branched;
                if (!g_api->select_candidate(g_explore, i)) continue;
                const std::string branchSent = explorePreview();
                add(branchSent);

                // Look one more segment ahead for consecutive homophone pairs (e.g. "网上" + "订")
                if (secondK > 0 && step < 3 && out.size() < 20 && !g_abortExplore) {
                    const size_t rem2 = rem - len;
                    auto cands2 = exploreCandidates(secondK * 2 + 4);
                    int b2 = 0;
                    bool seenPartial2 = false;
                    for (size_t j = 0; j < cands2.size() && b2 < secondK && !g_abortExplore; ++j) {
                        const auto& [t2, l2] = cands2[j];
                        if (l2 > 0 && l2 < rem2) seenPartial2 = true;
                        if (l2 == 0 || l2 > rem2) continue;
                        if (l2 == rem2) {
                            if (!seenPartial2) { add(prefixText + txt + t2); ++b2; }
                            continue;
                        }
                        if (j == 0) continue; // j==0 is already in branchSent
                        ++b2;
                        if (g_api->select_candidate(g_explore, j)) {
                            add(explorePreview());
                            if (!restoreSpine(spine) || !g_api->select_candidate(g_explore, i)) break;
                        }
                    }
                }
                if (!restoreSpine(spine)) break;
            }

            if (spineIdx < 0) break;
            const auto [sTxt, sLen] = cands[spineIdx];
            if (sLen >= rem) break; // reached final segment (its alternatives were just read above!)
            if (!g_api->select_candidate(g_explore, static_cast<size_t>(spineIdx))) break;
            spine.push_back(static_cast<size_t>(spineIdx));
            prefixText += sTxt;
            covered += sLen;
        }

        g_api->clear_composition(g_explore);
        RIME_STRUCT(RimeCommit, commit); // defensive: never leave a commit pending
        if (g_api->get_commit(g_explore, &commit)) g_api->free_commit(&commit);
    }
    std::vector<const char*> items;
    for (auto& s : out) items.push_back(s.c_str());
    return stringArray(env, items);
}

JNI_FN(jobjectArray, nativeQueryTop)(JNIEnv* env, jobject, jstring schema, jstring input, jint maxCands, jboolean traditional) {
    std::lock_guard<std::mutex> lock(g_lock);
    std::vector<std::string> out;
    if (g_initialized) {
        if (!g_explore || !g_api->find_session(g_explore)) g_explore = g_api->create_session();
        std::string sch = fromJava(env, schema);
        char cur[128] = {0};
        if (!g_api->get_current_schema(g_explore, cur, sizeof(cur)) || sch != cur)
            g_api->select_schema(g_explore, sch.c_str());
        g_api->set_option(g_explore, "emoji", False);
        g_api->set_option(g_explore, "traditionalization", traditional ? True : False);
        std::string in = fromJava(env, input);
        exploreFeed(in);

        auto add = [&](const std::string& s) {
            if (!s.empty() && static_cast<int>(out.size()) < maxCands && std::find(out.begin(), out.end(), s) == out.end())
                out.push_back(s);
        };
        add(explorePreview());
        auto cands = exploreCandidates(std::max<int>(maxCands * 2, 6));
        for (const auto& [txt, len] : cands) {
            if (len > 0) add(txt);
            if (static_cast<int>(out.size()) >= maxCands) break;
        }
        g_api->clear_composition(g_explore);
        RIME_STRUCT(RimeCommit, commit);
        if (g_api->get_commit(g_explore, &commit)) g_api->free_commit(&commit);
    }
    std::vector<const char*> items;
    for (auto& s : out) items.push_back(s.c_str());
    return stringArray(env, items);
}

JNI_FN(void, nativeSyncUserData)(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_initialized) g_api->sync_user_data();
}
