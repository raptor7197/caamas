#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define TAG "EmbedBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct EmbedHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    int            n_embd = 0;
};

extern "C"
JNIEXPORT jlong JNICALL
Java_com_main_agent_rag_EmbeddingEngine_nativeLoadEmbeddingModel(
    JNIEnv* env, jobject,
    jstring j_path,
    jint    n_ctx,
    jint    n_threads)
{
    const char* path = env->GetStringUTFChars(j_path, nullptr);
    LOGI("Loading embedding model: %s  ctx=%d  threads=%d", path, n_ctx, n_threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(j_path, path);

    if (!model) {
        LOGE("Failed to load embedding model");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)n_ctx;
    cparams.n_threads       = (uint32_t)n_threads;
    cparams.n_threads_batch = (uint32_t)n_threads;
    cparams.embeddings      = true;
    cparams.pooling_type    = LLAMA_POOLING_TYPE_MEAN;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create embedding context");
        llama_model_free(model);
        return 0L;
    }

    int n_embd = llama_model_n_embd(model);
    LOGI("Embedding model loaded. dim=%d", n_embd);

    auto* h = new EmbedHandle();
    h->model  = model;
    h->ctx    = ctx;
    h->n_embd = n_embd;
    return reinterpret_cast<jlong>(h);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_main_agent_rag_EmbeddingEngine_nativeEmbedText(
    JNIEnv* env, jobject, jlong j_handle, jstring j_text)
{
    auto* h = reinterpret_cast<EmbedHandle*>(j_handle);
    if (!h || !h->model || !h->ctx) {
        LOGE("nativeEmbedText: invalid handle");
        return nullptr;
    }

    const char* text_cstr = env->GetStringUTFChars(j_text, nullptr);
    std::string text(text_cstr);
    env->ReleaseStringUTFChars(j_text, text_cstr);

    const llama_vocab* vocab = llama_model_get_vocab(h->model);

    std::vector<llama_token> tokens(text.size() + 32);
    int n_tokens = llama_tokenize(
        vocab,
        text.c_str(), (int32_t)text.size(),
        tokens.data(), (int32_t)tokens.size(),
        /*add_special=*/true,
        /*parse_special=*/true);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens + 16);
        n_tokens = llama_tokenize(
            vocab,
            text.c_str(), (int32_t)text.size(),
            tokens.data(), (int32_t)tokens.size(),
            true, true);
    }

    if (n_tokens <= 0) {
        LOGE("Tokenization failed for embedding text");
        return nullptr;
    }
    tokens.resize(n_tokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t)tokens.size());
    if (llama_encode(h->ctx, batch) != 0) {
        LOGE("llama_encode failed");
        return nullptr;
    }

    float* embd = llama_get_embeddings_seq(h->ctx, 0);
    if (!embd) {
        LOGE("llama_get_embeddings_seq returned null");
        return nullptr;
    }

    jfloatArray result = env->NewFloatArray(h->n_embd);
    if (result) {
        env->SetFloatArrayRegion(result, 0, h->n_embd, embd);
    }
    return result;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_main_agent_rag_EmbeddingEngine_nativeFreeEmbeddingModel(
    JNIEnv*, jobject, jlong j_handle)
{
    auto* h = reinterpret_cast<EmbedHandle*>(j_handle);
    if (!h) return;
    LOGI("Freeing embedding model");
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}
