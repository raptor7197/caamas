#include <jni.h>
#include <cstdio>
#include <cstring>
#include <vector>
#include <string>
#include <mutex>

#include "arc_cache.h"
#include "psi_monitor.h"
#include "working_set.h"
#include "thermal_monitor.h"
#include "confidence_gate.h"
#include "gru_inference.h"

static std::mutex g_mutex;
static camms::ArcCache* g_arc = nullptr;
static camms::PsiMonitor* g_psi = nullptr;
static camms::WorkingSetMonitor* g_ws = nullptr;
static camms::ThermalMonitor* g_thermal = nullptr;
static camms::ConfidenceGate* g_gate = nullptr;
static camms::GruInferenceEngine* g_gru = nullptr;
static bool g_initialized = false;

static JavaVM* g_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM*, void*) {
    delete g_arc; g_arc = nullptr;
    delete g_psi; g_psi = nullptr;
    delete g_ws; g_ws = nullptr;
    delete g_thermal; g_thermal = nullptr;
    delete g_gate; g_gate = nullptr;
    delete g_gru; g_gru = nullptr;
    g_initialized = false;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_camms_NativeBridge_cammsInit(JNIEnv* env, jclass, jstring configPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_initialized) return JNI_TRUE;

    g_arc = new camms::ArcCache(256 * 1024);
    g_psi = new camms::PsiMonitor(1000);
    g_ws = new camms::WorkingSetMonitor(5000);
    g_thermal = new camms::ThermalMonitor(2000);
    g_gate = new camms::ConfidenceGate(0.60f, 0.50f, 7);
    g_gru = new camms::GruInferenceEngine();

    g_initialized = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_camms_NativeBridge_cammsStart(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_initialized) return JNI_FALSE;
    if (g_psi) g_psi->start();
    if (g_thermal) g_thermal->start();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_camms_NativeBridge_cammsStop(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_psi) g_psi->stop();
    if (g_thermal) g_thermal->stop();
}

JNIEXPORT void JNICALL
Java_com_camms_NativeBridge_cammsShutdown(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_psi) g_psi->stop();
    if (g_thermal) g_thermal->stop();
    delete g_arc; g_arc = nullptr;
    delete g_psi; g_psi = nullptr;
    delete g_ws; g_ws = nullptr;
    delete g_thermal; g_thermal = nullptr;
    delete g_gate; g_gate = nullptr;
    delete g_gru; g_gru = nullptr;
    g_initialized = false;
}

JNIEXPORT jlong JNICALL
Java_com_camms_NativeBridge_getTotalPssKb(JNIEnv* env, jclass) {
    if (!g_ws) return 0;
    return g_ws->stats().total_pss_kb;
}

JNIEXPORT jlong JNICALL
Java_com_camms_NativeBridge_getTotalRssKb(JNIEnv* env, jclass) {
    if (!g_ws) return 0;
    return g_ws->stats().total_rss_kb;
}

JNIEXPORT jlong JNICALL
Java_com_camms_NativeBridge_getTotalSwapKb(JNIEnv* env, jclass) {
    if (!g_ws) return 0;
    return g_ws->stats().total_swap_kb;
}

JNIEXPORT jint JNICALL
Java_com_camms_NativeBridge_getActiveProcessCount(JNIEnv* env, jclass) {
    if (!g_ws) return 0;
    return static_cast<jint>(g_ws->stats().active_processes);
}

JNIEXPORT jfloat JNICALL
Java_com_camms_NativeBridge_getArcHitRate(JNIEnv* env, jclass) {
    if (!g_arc) return 0.0f;
    return g_arc->hit_rate();
}

JNIEXPORT jlong JNICALL
Java_com_camms_NativeBridge_getArcUsageKb(JNIEnv* env, jclass) {
    if (!g_arc) return 0;
    return g_arc->current_usage_kb();
}

JNIEXPORT jintArray JNICALL
Java_com_camms_NativeBridge_predictNextApp(JNIEnv* env, jclass, jintArray appHistory) {
    jsize len = env->GetArrayLength(appHistory);
    jint* elems = env->GetIntArrayElements(appHistory, nullptr);

    std::vector<int32_t> seq;
    for (jsize i = 0; i < len; i++) seq.push_back(static_cast<int32_t>(elems[i]));
    env->ReleaseIntArrayElements(appHistory, elems, JNI_ABORT);

    camms::Prediction pred;
    if (g_gru) {
        pred = g_gru->predict(seq);
    } else {
        pred.app_id = -1;
        pred.confidence = 0.0f;
    }

    jint result[4] = {
        static_cast<jint>(pred.app_id),
        static_cast<jint>(pred.confidence * 1000),
        pred.top_k.size() > 1 ? static_cast<jint>(pred.top_k[1]) : -1,
        pred.top_k.size() > 2 ? static_cast<jint>(pred.top_k[2]) : -1,
    };

    jintArray out = env->NewIntArray(4);
    env->SetIntArrayRegion(out, 0, 4, result);
    return out;
}

JNIEXPORT jfloat JNICALL
Java_com_camms_NativeBridge_getPredictionConfidence(JNIEnv* env, jclass) {
    return 0.0f; // last prediction confidence tracked in Kotlin layer
}

JNIEXPORT void JNICALL
Java_com_camms_NativeBridge_recordAppLaunch(JNIEnv* env, jclass, jint appId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_arc) {
        g_arc->record_access(static_cast<int32_t>(appId), 1024);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_camms_NativeBridge_preloadApp(JNIEnv* env, jclass, jint appId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_arc) return JNI_FALSE;

    // Mark as recently used to prevent eviction
    g_arc->record_access(static_cast<int32_t>(appId), 1024, 1.0f);
    g_arc->pin_app(static_cast<int32_t>(appId), true);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_com_camms_NativeBridge_compactZram(JNIEnv* env, jclass, jlong targetBytes) {
    // Trigger zRAM compaction via sysfs
    FILE* f = fopen("/sys/block/zram0/compact", "w");
    if (f) {
        fprintf(f, "1");
        fclose(f);
        return targetBytes; // signal success
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_camms_NativeBridge_evictApp(JNIEnv* env, jclass, jint appId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_arc) {
        int32_t evicted = g_arc->evict_one();
        return evicted == static_cast<int32_t>(appId) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_camms_NativeBridge_getThermalLevel(JNIEnv* env, jclass) {
    if (!g_thermal) return 0;
    return static_cast<jint>(g_thermal->current_level());
}

JNIEXPORT jfloat JNICALL
Java_com_camms_NativeBridge_getThermalHeadroom(JNIEnv* env, jclass) {
    if (!g_thermal) return 0.0f;
    return g_thermal->headroom();
}

JNIEXPORT void JNICALL
Java_com_camms_NativeBridge_setThermalHeadroom(JNIEnv* env, jclass, jfloat headroom) {
    // ThermalMonitor reads from sysfs; this override is for Android API callbacks
    // In production: store in shared atomic for thermal_monitor
}

JNIEXPORT jfloatArray JNICALL
Java_com_camms_NativeBridge_getModelWeights(JNIEnv* env, jclass) {
    if (!g_gru) return nullptr;
    auto weights = g_gru->export_weights();
    jfloatArray result = env->NewFloatArray(weights.size());
    if (!weights.empty()) {
        env->SetFloatArrayRegion(result, 0, weights.size(), weights.data());
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_camms_NativeBridge_setModelWeights(JNIEnv* env, jclass, jfloatArray weights) {
    if (!g_gru) return JNI_FALSE;
    jsize len = env->GetArrayLength(weights);
    jfloat* elems = env->GetFloatArrayElements(weights, nullptr);
    bool ok = g_gru->load_weights(elems, len);
    env->ReleaseFloatArrayElements(weights, elems, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_camms_NativeBridge_setConfidenceThreshold(JNIEnv* env, jclass, jfloat high, jfloat low) {
    if (g_gate) {
        g_gate->set_thresholds(high, low);
    }
}

JNIEXPORT void JNICALL
Java_com_camms_NativeBridge_setCacheCapacityKb(JNIEnv* env, jclass, jlong capacityKb) {
    // ARC cache capacity is set at construction; would need re-creation
}

} // extern "C"
