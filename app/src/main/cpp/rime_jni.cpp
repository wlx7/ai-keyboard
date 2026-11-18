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
bool g_initialized = false;

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
    if (g_initialized) {
        if (g_session) { g_api->destroy_session(g_session); g_session = 0; }
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
    if (!g_initialized) return;
    if (g_session) { g_api->destroy_session(g_session); g_session = 0; }
    g_api->finalize();
    g_initialized = false;
}

JNI_FN(jboolean, nativeProcessKey)(JNIEnv*, jobject, jint keycode, jint mask) {
    g_abortExplore = true;
    std::lock_guard<std::mutex> lock(g_lock);
    if (!ensureSession()) return JNI_FALSE;
    return g_api->process_key(g_session, keycode, mask) ? JNI_TRUE : JNI_FALSE;
}

JNI_FN(jstring, nativeGetCommit)(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
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
    return g_api->select_candidate(g_session, static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
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
    if (ensureSession()) g_api->clear_composition(g_session);
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
RimeSessionId g_explore = 0;

size_t utf8Len(const char* s) {
    size_t n = 0;
    for (; s && *s; ++s) if ((static_cast<unsigned char>(*s) & 0xC0) != 0x80) ++n;
    return n;
}

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
            if (!s.empty() && std::find(out.begin(), out.end(), s) == out.end()) out.push_back(s);
        };
        exploreFeed(in);
        add(explorePreview());
        auto level1 = exploreCandidates(firstK * 3);
        int tried = 0;
        for (size_t i = 0; i < level1.size() && tried < firstK && !g_abortExplore; ++i) {
            const auto& [text, len] = level1[i];
            if (len == static_cast<size_t>(syllables)) { add(text); continue; } // whole sentence
            if (len == 0 || len > static_cast<size_t>(syllables)) continue;
            ++tried;
            if (!g_api->select_candidate(g_explore, i)) continue;
            const std::string afterI = explorePreview();
            add(afterI);
            const size_t rest = syllables - len;
            auto level2 = exploreCandidates(secondK * 3);
            int tried2 = 0;
            for (size_t j = 0; j < level2.size() && tried2 < secondK && !g_abortExplore; ++j) {
                const auto& [t2, l2] = level2[j];
                if (l2 == rest) { add(text + t2); continue; }
                if (l2 == 0 || l2 > rest) continue;
                ++tried2;
                if (!g_api->select_candidate(g_explore, j)) continue;
                add(explorePreview());
                exploreUndo(in);
                // Back to "word i selected"? If BackSpace unwound further, rebuild that state.
                if (explorePreview() != afterI) { exploreFeed(in); g_api->select_candidate(g_explore, i); }
            }
            exploreUndo(in);
        }
        g_api->clear_composition(g_explore);
        RIME_STRUCT(RimeCommit, commit); // defensive: never leave a commit pending
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
