#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <memory>
#include <cstring>

// llama.cpp public headers
#include "llama.h"
#include "common/common.h"

#define TAG "AgentNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// Safety margin (in tokens) kept free at the top of the KV cache so the
// generation loop always stops before llama_decode() would be asked to
// write past the end of the context window.
constexpr int kGenMargin = 4;

// ─── Handle struct ────────────────────────────────────────────────────────────
struct LlamaHandle {
    llama_model*             model       = nullptr;
    llama_context*           ctx         = nullptr;
    const llama_vocab*       vocab       = nullptr;
    int                      n_ctx       = 0;
    int                      n_vocab     = 0;
    std::atomic<bool>        cancel{false};
    // KV-cache prefix reuse: track what was encoded last call
    std::vector<llama_token> prev_tokens;
    int                      n_past      = 0;
};

// ─── Callback method IDs (cached once) ────────────────────────────────────────
static jmethodID g_onToken    = nullptr;
static jmethodID g_onDone     = nullptr;
static jmethodID g_onError    = nullptr;

// ─── JNI_OnLoad ───────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    llama_backend_init();
    LOGI("llama_backend_init() done  (llama.cpp built-in)");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM*, void*) {
    llama_backend_free();
}

// ─── Cache callback method IDs from the InferenceCallback interface ───────────
static bool ensure_callback_ids(JNIEnv* env, jobject cb) {
    if (g_onToken) return true;
    jclass cls = env->GetObjectClass(cb);
    g_onToken = env->GetMethodID(cls, "onToken",    "(Ljava/lang/String;)Z");
    g_onDone  = env->GetMethodID(cls, "onComplete", "(IJ)V");
    g_onError = env->GetMethodID(cls, "onError",    "(Ljava/lang/String;)V");
    return g_onToken && g_onDone && g_onError;
}

// ─── nativeLoadModel ─────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jlong JNICALL
Java_com_main_agent_llm_LlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject,
        jstring j_path,
        jint    n_ctx,
        jint    n_threads,
        jboolean use_gpu)
{
    const char* path = env->GetStringUTFChars(j_path, nullptr);
    LOGI("Loading model: %s  ctx=%d  threads=%d  gpu=%d", path, n_ctx, n_threads, use_gpu);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = use_gpu ? 99 : 0;  // 99 = offload all layers if GPU found

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(j_path, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx            = (uint32_t)n_ctx;
    cparams.n_threads        = (uint32_t)n_threads;
    cparams.n_threads_batch  = (uint32_t)n_threads;
    cparams.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0L;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    auto* handle      = new LlamaHandle();
    handle->model     = model;
    handle->ctx       = ctx;
    handle->vocab     = vocab;
    handle->n_ctx     = n_ctx;
    handle->n_vocab   = llama_vocab_n_tokens(vocab);
    LOGI("Model loaded. vocab=%d  ctx=%d", handle->n_vocab, n_ctx);

    return reinterpret_cast<jlong>(handle);
}

// ─── nativeInfer ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_main_agent_llm_LlamaEngine_nativeInfer(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    j_handle,
        jstring  j_prompt,
        jint     j_max_tokens,
        jfloat   j_temperature,
        jobject  j_callback)
{
    auto* h = reinterpret_cast<LlamaHandle*>(j_handle);
    if (!h || !h->model || !h->ctx) {
        LOGE("nativeInfer: invalid handle");
        return JNI_FALSE;
    }
    if (!ensure_callback_ids(env, j_callback)) {
        LOGE("nativeInfer: could not resolve callback methods");
        return JNI_FALSE;
    }

    h->cancel.store(false);

    // ── Build prompt string ──────────────────────────────────────────────────
    const char* prompt_cstr = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(j_prompt, prompt_cstr);

    // ── Tokenize full prompt ──────────────────────────────────────────────────
    const int n_prompt_max = h->n_ctx - 64;
    std::vector<llama_token> tokens(prompt.size() + 32);
    int n_tokens = llama_tokenize(
        h->vocab,
        prompt.c_str(), (int32_t)prompt.size(),
        tokens.data(),  (int32_t)tokens.size(),
        /*add_special=*/true,
        /*parse_special=*/true);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens + 16);
        n_tokens = llama_tokenize(
            h->vocab,
            prompt.c_str(), (int32_t)prompt.size(),
            tokens.data(),  (int32_t)tokens.size(),
            true, true);
    }
    if (n_tokens < 0 || n_tokens > n_prompt_max) {
        LOGE("Prompt too long: %d tokens (max %d)", n_tokens, n_prompt_max);
        jstring jerrstr = env->NewStringUTF("Prompt exceeds context window");
        env->CallVoidMethod(j_callback, g_onError, jerrstr);
        env->DeleteLocalRef(jerrstr);
        return JNI_FALSE;
    }
    tokens.resize(n_tokens);

    // ── KV-cache prefix reuse ─────────────────────────────────────────────────
    // Find how many leading tokens match the previous call's tokens.
    // If there is a common prefix we can keep those KV entries and only
    // decode the new suffix — O(new_tokens) instead of O(total_tokens).
    int n_common = 0;
    {
        int prev_sz = (int)h->prev_tokens.size();
        int limit   = std::min(prev_sz, n_tokens);
        while (n_common < limit && h->prev_tokens[n_common] == tokens[n_common])
            n_common++;
    }

    if (n_common > 0 && n_common <= h->n_past) {
        // Trim KV cache back to the common prefix position
        llama_memory_seq_rm(llama_get_memory(h->ctx), 0, (llama_pos)n_common, -1);
        h->n_past = n_common;
        LOGD("KV reuse: kept=%d  new=%d", n_common, n_tokens - n_common);
    } else {
        // No usable prefix — clear and re-encode everything
        llama_memory_clear(llama_get_memory(h->ctx), true);
        h->n_past = 0;
        LOGD("KV clear: encoding %d tokens from scratch", n_tokens);
    }

    // ── Encode only the new (non-cached) suffix ───────────────────────────────
    if (n_tokens > h->n_past) {
        llama_batch batch = llama_batch_get_one(
            tokens.data() + h->n_past, n_tokens - h->n_past);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("llama_decode failed on prompt suffix");
            jstring je = env->NewStringUTF("Inference error: decode failed");
            env->CallVoidMethod(j_callback, g_onError, je);
            env->DeleteLocalRef(je);
            return JNI_FALSE;
        }
        h->n_past = n_tokens;
    }

    // Remember for next call
    h->prev_tokens = tokens;

    // ── Sampler setup ─────────────────────────────────────────────────────────
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    float temp = (float)j_temperature;
    if (temp <= 0.0f) {
        // Greedy
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // ── Generation loop ───────────────────────────────────────────────────────
    int n_generated = 0;
    int max_tokens  = (int)j_max_tokens;

    // Make callback a global ref to safely call across potentially multiple frames
    jobject cb_global = env->NewGlobalRef(j_callback);

    while (n_generated < max_tokens && !h->cancel.load()) {
        if (h->n_past >= h->n_ctx - kGenMargin) {
            LOGI("Context window reached (n_past=%d n_ctx=%d) — stopping generation early", h->n_past, h->n_ctx);
            break;
        }

        llama_token token = llama_sampler_sample(smpl, h->ctx, -1);

        // EOG or EOS
        if (llama_vocab_is_eog(h->vocab, token)) break;

        // Convert token to UTF-8 string piece
        char   piece_buf[64];
        int    n_piece = llama_token_to_piece(h->vocab, token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece < 0) {
            // Piece too large — skip
            piece_buf[0] = '\0';
            n_piece = 0;
        }
        std::string piece(piece_buf, n_piece > 0 ? n_piece : 0);

        // Deliver token to Kotlin
        if (!piece.empty()) {
            jstring jpiece = env->NewStringUTF(piece.c_str());
            jboolean cont  = env->CallBooleanMethod(cb_global, g_onToken, jpiece);
            env->DeleteLocalRef(jpiece);
            if (!cont || h->cancel.load()) break;
        }

        // Next decode
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(h->ctx, batch) != 0) {
            LOGE("llama_decode failed during generation at token %d", n_generated);
            break;
        }
        h->prev_tokens.push_back(token);
        h->n_past++;
        n_generated++;
    }

    llama_sampler_free(smpl);

    // Signal completion
    jlong dur_ms = 0;  // could add timing here
    env->CallVoidMethod(cb_global, g_onDone, (jint)n_generated, dur_ms);
    env->DeleteGlobalRef(cb_global);

    return JNI_TRUE;
}

// ─── nativeCancelInfer ────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_main_agent_llm_LlamaEngine_nativeCancelInfer(
        JNIEnv*, jobject, jlong j_handle)
{
    auto* h = reinterpret_cast<LlamaHandle*>(j_handle);
    if (h) h->cancel.store(true);
}

// ─── nativeFreeModel ─────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_main_agent_llm_LlamaEngine_nativeFreeModel(
        JNIEnv*, jobject, jlong j_handle)
{
    auto* h = reinterpret_cast<LlamaHandle*>(j_handle);
    if (!h) return;
    LOGI("Freeing model handle");
    h->prev_tokens.clear();
    h->n_past = 0;
    if (h->ctx)   llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

// ─── nativeGetVocabSize ───────────────────────────────────────────────────────
extern "C"
JNIEXPORT jint JNICALL
Java_com_main_agent_llm_LlamaEngine_nativeGetVocabSize(
        JNIEnv*, jobject, jlong j_handle)
{
    auto* h = reinterpret_cast<LlamaHandle*>(j_handle);
    return h ? (jint)h->n_vocab : 0;
}

// ─── nativeApplyChatTemplate ──────────────────────────────────────────────────
// Formats a messages array through the model's built-in chat template.
// j_messages: String[] alternating ["role","content","role","content"...]
//
// Uses the correct template for each model family based on vocabulary size:
//   Llama 3.1  (n_vocab == 128256) → <|begin_of_text|>role\ncontent\n...
//   Gemma 2    (n_vocab == 256000) → <bos>role\ncontent\n...
//   Default                         → simple "role:\ncontent\n\n"
extern "C"
JNIEXPORT jstring JNICALL
Java_com_main_agent_llm_LlamaEngine_nativeApplyChatTemplate(
        JNIEnv* env, jobject, jlong j_handle, jobjectArray j_messages, jboolean add_ass)
{
    auto* h = reinterpret_cast<LlamaHandle*>(j_handle);
    if (!h || !h->model) return env->NewStringUTF("");

    int n_msgs = env->GetArrayLength(j_messages) / 2;
    if (n_msgs <= 0) return env->NewStringUTF("");

    // Determine model family by vocab size
    bool is_llama  = (h->n_vocab == 128256);  // Llama 3.x
    bool is_gemma  = (h->n_vocab == 256000);  // Gemma 2
    bool is_qwen   = (h->n_vocab == 151936);  // Qwen 2.5

    std::string prompt;
    for (int i = 0; i < n_msgs; i++) {
        jstring jrole    = (jstring)env->GetObjectArrayElement(j_messages, i * 2);
        jstring jcontent = (jstring)env->GetObjectArrayElement(j_messages, i * 2 + 1);

        if (!jrole || !jcontent) {
            env->DeleteLocalRef(jrole);
            env->DeleteLocalRef(jcontent);
            continue;
        }

        const char* role_str    = env->GetStringUTFChars(jrole, nullptr);
        const char* content_str = env->GetStringUTFChars(jcontent, nullptr);

        if (is_qwen) {
            // Qwen 2.5 ChatML format
            if (strcmp(role_str, "system") == 0) {
                prompt += "<|im_start|>system\n";
                prompt += content_str;
                prompt += "<|im_end|>\n";
            } else if (strcmp(role_str, "user") == 0 || strcmp(role_str, "tool") == 0) {
                prompt += "<|im_start|>user\n";
                prompt += content_str;
                prompt += "<|im_end|>\n";
            } else if (strcmp(role_str, "assistant") == 0) {
                prompt += "<|im_start|>assistant\n";
                prompt += content_str;
                prompt += "<|im_end|>\n";
            }
        } else if (is_gemma) {
            // Gemma 2 format
            if (i == 0) prompt += "<bos>";
            if (strcmp(role_str, "system") == 0) {
                prompt += "<start_of_turn>user\n";
                prompt += content_str;
                prompt += "<end_of_turn>\n";
            } else if (strcmp(role_str, "user") == 0) {
                prompt += "<start_of_turn>user\n";
                prompt += content_str;
                prompt += "<end_of_turn>\n";
            } else if (strcmp(role_str, "assistant") == 0) {
                prompt += "<start_of_turn>model\n";
                prompt += content_str;
                prompt += "<end_of_turn>\n";
            }
        } else if (is_llama) {
            // Llama 3.1 format
            if (i == 0 && strcmp(role_str, "system") == 0) {
                prompt += "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n";
                prompt += content_str;
                prompt += "<|eot_id|>";
            } else if (strcmp(role_str, "user") == 0) {
                prompt += "<|start_header_id|>user<|end_header_id|>\n\n";
                prompt += content_str;
                prompt += "<|eot_id|>";
            } else if (strcmp(role_str, "assistant") == 0) {
                prompt += "<|start_header_id|>assistant<|end_header_id|>\n\n";
                prompt += content_str;
                prompt += "<|eot_id|>";
            }
        } else {
            // Generic fallback
            if (i == 0) prompt = "";
            prompt += role_str;
            prompt += ":\n";
            prompt += content_str;
            prompt += "\n\n";
        }

        env->ReleaseStringUTFChars(jrole,    role_str);
        env->ReleaseStringUTFChars(jcontent, content_str);
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);
    }

    if (add_ass) {
        if (is_qwen) {
            prompt += "<|im_start|>assistant\n";
        } else if (is_gemma) {
            prompt += "<start_of_turn>model\n";
        } else if (is_llama) {
            prompt += "<|start_header_id|>assistant<|end_header_id|>\n\n";
        } else {
            prompt += "assistant:\n";
        }
    }

    return env->NewStringUTF(prompt.c_str());
}
