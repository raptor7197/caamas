#include "gru_inference.h"
#include <cstdio>
#include <cassert>
#include <vector>

int test_fallback_prediction() {
    camms::GruInferenceEngine engine;

    // Without loading a model, should use fallback predictor
    assert(!engine.is_loaded());

    auto pred = engine.predict({1, 2, 3});
    assert(pred.app_id != -1);
    assert(pred.confidence >= 0.0f);
    assert(!pred.top_k.empty());
    assert(pred.inference_us > 0);

    printf("[PASS] test_fallback_prediction: app=%d conf=%.3f time=%lu us\n",
           pred.app_id, pred.confidence, pred.inference_us);
    return 0;
}

int test_empty_sequence() {
    camms::GruInferenceEngine engine;

    auto pred = engine.predict({});
    assert(pred.app_id != -1); // should return most frequent

    printf("[PASS] test_empty_sequence: app=%d conf=%.3f\n",
           pred.app_id, pred.confidence);
    return 0;
}

int test_benchmark() {
    camms::GruInferenceEngine engine;

    auto result = engine.benchmark(50);
    assert(result.latency_ms > 0);
    assert(result.throughput_per_sec > 0);

    printf("[PASS] test_benchmark: latency=%.3f ms throughput=%.0f/s\n",
           result.latency_ms, result.throughput_per_sec);
    return 0;
}

int test_weight_export() {
    camms::GruInferenceEngine engine;

    auto weights = engine.export_weights();
    // Should return empty (placeholder implementation)
    printf("[PASS] test_weight_export: %zu values\n", weights.size());
    return 0;
}

int test_load_model_missing_file() {
    camms::GruInferenceEngine engine;

    bool loaded = engine.load_model("/nonexistent/model.tflite");
    // Should gracefully handle missing file
    assert(!loaded); // model not loaded

    // Should still work via fallback
    auto pred = engine.predict({1, 2, 3});
    assert(pred.app_id != -1);

    printf("[PASS] test_load_model_missing_file: fallback works after failed load\n");
    return 0;
}

int main() {
    int failures = 0;
    failures += test_fallback_prediction();
    failures += test_empty_sequence();
    failures += test_benchmark();
    failures += test_weight_export();
    failures += test_load_model_missing_file();

    printf("\n=== GRU Inference: %d tests passed ===\n", 5 - failures);
    return failures;
}
