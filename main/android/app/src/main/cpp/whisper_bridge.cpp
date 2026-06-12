#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <mutex>

#include "whisper.h"

#define TAG "WhisperBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct WhisperHandle {
    struct whisper_context * ctx = nullptr;
    int                      n_threads = 2;
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_main_agent_voice_WhisperSTT_nativeLoadWhisperModel(
    JNIEnv * env, jobject,
    jstring j_path,
    jint    n_threads)
{
    const char * path = env->GetStringUTFChars(j_path, nullptr);
    LOGI("Loading whisper model: %s threads=%d", path, n_threads);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    struct whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(j_path, path);

    if (!ctx) {
        LOGE("Failed to load whisper model");
        return 0L;
    }

    auto * h = new WhisperHandle();
    h->ctx = ctx;
    h->n_threads = (int)n_threads;
    LOGI("Whisper model loaded successfully");

    return reinterpret_cast<jlong>(h);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_main_agent_voice_WhisperSTT_nativeTranscribe(
    JNIEnv * env, jobject,
    jlong   j_handle,
    jfloatArray j_pcm,
    jint    j_sample_rate)
{
    auto * h = reinterpret_cast<WhisperHandle *>(j_handle);
    if (!h || !h->ctx) {
        LOGE("nativeTranscribe: invalid handle");
        return env->NewStringUTF("");
    }

    jfloat * pcm_arr = env->GetFloatArrayElements(j_pcm, nullptr);
    jsize   n_samples = env->GetArrayLength(j_pcm);
    LOGI("Transcribing: n_samples=%d sample_rate=%d", n_samples, j_sample_rate);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads       = h->n_threads;
    params.language        = "en";
    params.no_timestamps   = true;
    params.print_special   = false;
    params.print_progress  = false;
    params.print_realtime  = false;
    params.print_timestamps = false;

    int ret = whisper_full(h->ctx, params, pcm_arr, (int)n_samples);
    env->ReleaseFloatArrayElements(j_pcm, pcm_arr, JNI_ABORT);

    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
        return env->NewStringUTF("");
    }

    int n_segments = whisper_full_n_segments(h->ctx);
    std::string full_text;

    for (int i = 0; i < n_segments; ++i) {
        const char * seg_text = whisper_full_get_segment_text(h->ctx, i);
        if (seg_text) {
            full_text += seg_text;
        }
    }

    LOGI("Transcription done: %d segments, %zu chars", n_segments, full_text.size());
    return env->NewStringUTF(full_text.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_main_agent_voice_WhisperSTT_nativeFreeWhisperModel(
    JNIEnv *, jobject,
    jlong j_handle)
{
    auto * h = reinterpret_cast<WhisperHandle *>(j_handle);
    if (!h) return;
    LOGI("Freeing whisper model");
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}
