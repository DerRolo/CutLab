#include <jni.h>
#include <string>
#include <atomic>

#ifdef HAVE_WHISPER
#include "whisper.h"

static std::atomic<bool> g_abort(false);
static bool abort_cb(void *) { return g_abort.load(); }

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
Java_de_cutlab_app_WhisperLib_initContext(JNIEnv *env, jobject, jstring modelPath) {
#ifdef HAVE_WHISPER
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
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
    wp.max_len = 1;                            // ein Wort pro Segment
    wp.split_on_word = true;
    wp.suppress_blank = true;
    wp.initial_prompt = promptStr.empty() ? nullptr : promptStr.c_str();   // Namen/Woerter als Hinweis
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = nullptr;

    int rc = whisper_full(ctx, wp, samples, n);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    if (rc != 0) return nullptr;

    int ns = whisper_full_n_segments(ctx);
    jclass strClass = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(ns, strClass, nullptr);
    for (int i = 0; i < ns; i++) {
        long long t0 = (long long) whisper_full_get_segment_t0(ctx, i) * 10;   // 10-ms-Einheiten -> ms
        long long t1 = (long long) whisper_full_get_segment_t1(ctx, i) * 10;
        std::string row = std::to_string(t0) + "|" + std::to_string(t1) + "|" +
                          sanitize(whisper_full_get_segment_text(ctx, i));
        jstring js = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(out, i, js);
        env->DeleteLocalRef(js);
    }
    return out;
#else
    return nullptr;
#endif
}

}  // extern "C"
