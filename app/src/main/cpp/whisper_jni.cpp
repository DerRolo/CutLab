#include <jni.h>
#include <string>
#include <atomic>
#include <vector>

#ifdef HAVE_WHISPER
#include "whisper.h"

static std::atomic<bool> g_abort(false);
static bool abort_cb(void *) { return g_abort.load(); }

static std::atomic<int> g_progress(0);
static void progress_cb(struct whisper_context *, struct whisper_state *, int progress, void *) { g_progress = progress; }

// Nur gueltiges UTF-8 (ohne 4-Byte-Zeichen) durchlassen, damit NewStringUTF nie abstuerzt.
static std::string sanitize(const char *s) {
    std::string out;
    if (!s) return out;
    const unsigned char *p = reinterpret_cast<const unsigned char *>(s);
    while (*p) {
        if (*p < 0x80) { out += (char) *p; p++; }
        else if (*p >= 0xC2 && *p <= 0xDF && (p[1] & 0xC0) == 0x80) { out.append((const char *) p, 2); p += 2; }
        else if (*p >= 0xE0 && *p <= 0xEF && (p[1] & 0xC0) == 0x80 && (p[2] & 0xC0) == 0x80) { out.append((const char *) p, 3); p += 3; }
        else { p++; }
    }
    return out;
}
#endif

extern "C" {

JNIEXPORT jboolean JNICALL
Java_de_cutlab_app_WhisperLib_nativeAvailable(JNIEnv *, jobject) {
#ifdef HAVE_WHISPER
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_de_cutlab_app_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath, jboolean small) {
#ifdef HAVE_WHISPER
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.dtw_token_timestamps = true;                                   // genaue Wortzeiten
    cp.dtw_aheads_preset = small ? WHISPER_AHEADS_SMALL : WHISPER_AHEADS_BASE;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
#else
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_de_cutlab_app_WhisperLib_progress(JNIEnv *, jobject) {
#ifdef HAVE_WHISPER
    return g_progress.load();
#else
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_de_cutlab_app_WhisperLib_freeContext(JNIEnv *, jobject, jlong ptr) {
#ifdef HAVE_WHISPER
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
#endif
}

JNIEXPORT void JNICALL
Java_de_cutlab_app_WhisperLib_abort(JNIEnv *, jobject) {
#ifdef HAVE_WHISPER
    g_abort = true;
#endif
}

JNIEXPORT jobjectArray JNICALL
Java_de_cutlab_app_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ptr, jfloatArray audio,
                                         jstring lang, jstring prompt, jint threads) {
#ifdef HAVE_WHISPER
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return nullptr;
    g_abort = false;
    g_progress = 0;

    jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const char *clang = env->GetStringUTFChars(lang, nullptr);
    const char *cprompt = env->GetStringUTFChars(prompt, nullptr);
    std::string langStr(clang);
    std::string promptStr(cprompt);
    env->ReleaseStringUTFChars(lang, clang);
    env->ReleaseStringUTFChars(prompt, cprompt);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.language = langStr.c_str();             // "auto" = automatisch erkennen
    wp.n_threads = threads;
    wp.token_timestamps = true;                // Wort-Zeitstempel
    wp.progress_callback = progress_cb;
    wp.progress_callback_user_data = nullptr;
    wp.suppress_blank = true;
    wp.initial_prompt = promptStr.empty() ? nullptr : promptStr.c_str();   // Namen/Woerter als Hinweis
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, wp, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (rc != 0) return nullptr;

    // Woerter aus den Tokens bauen: neues Wort beginnt bei einem Token mit fuehrendem Leerzeichen
    struct Wd { long long start; long long segEnd; std::string text; };
    std::vector<Wd> words;
    const whisper_token eot = whisper_token_eot(ctx);
    int ns = whisper_full_n_segments(ctx);
    for (int i = 0; i < ns; i++) {
        long long segEnd = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        int nt = whisper_full_n_tokens(ctx, i);
        bool firstTok = true;
        for (int j = 0; j < nt; j++) {
            whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            if (td.id >= eot) continue;                               // Sondertokens (Zeitmarken, Sprache, ...)
            const char *tt = whisper_full_get_token_text(ctx, i, j);
            if (!tt || !*tt) continue;
            std::string t(tt);
            long long ts = (td.t_dtw >= 0 ? (long long) td.t_dtw : (long long) td.t0) * 10;   // 10-ms-Einheiten -> ms
            if (t[0] == ' ' || firstTok || words.empty()) words.push_back({ts, segEnd, t});
            else words.back().text += t;
            firstTok = false;
        }
    }
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray((jsize) words.size(), strClass, nullptr);
    for (size_t i = 0; i < words.size(); i++) {
        std::string row = std::to_string(words[i].start) + "|" + std::to_string(words[i].segEnd) + "|" + sanitize(words[i].text.c_str());
        jstring js = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(out, (jsize) i, js);
        env->DeleteLocalRef(js);
    }
    return out;
#else
    return nullptr;
#endif
}

}  // extern "C"
