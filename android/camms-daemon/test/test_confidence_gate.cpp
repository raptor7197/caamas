#include "confidence_gate.h"
#include <cstdio>
#include <cassert>

camms::PredictionResult make_prediction(int app_id, float conf) {
    camms::PredictionResult p{};
    p.predicted_app_id = app_id;
    p.confidence = conf;
    p.top_k = {app_id};
    p.top_k_scores = {conf};
    p.inference_time_us = 100;
    return p;
}

int test_high_confidence_preload() {
    camms::ConfidenceGate gate(0.6f, 0.5f);

    auto pred = make_prediction(42, 0.85f);
    auto decision = gate.evaluate(pred, 2, false, 0.1f);

    assert(decision.action == camms::ArbiterDecision::Action::PRELOAD);
    assert(decision.target_app_id == 42);
    assert(decision.confidence == 0.85f);

    printf("[PASS] test_high_confidence_preload\n");
    return 0;
}

int test_low_confidence_defer() {
    camms::ConfidenceGate gate(0.6f, 0.5f);

    auto pred = make_prediction(42, 0.3f);
    auto decision = gate.evaluate(pred, 2, false, 0.1f);

    assert(decision.action == camms::ArbiterDecision::Action::NONE);
    assert(decision.target_app_id == -1);

    printf("[PASS] test_low_confidence_defer\n");
    return 0;
}

int test_thermal_throttling() {
    camms::ConfidenceGate gate(0.6f, 0.5f);

    auto pred = make_prediction(42, 0.85f);
    auto decision = gate.evaluate(pred, 2, false, 0.9f);

    assert(decision.action == camms::ArbiterDecision::Action::THROTTLE);

    printf("[PASS] test_thermal_throttling\n");
    return 0;
}

int test_user_tier_thresholds() {
    camms::ConfidenceGate gate(0.6f, 0.5f);

    // Tier 0 (Markov): always predict
    auto pred = make_prediction(42, 0.1f);
    auto decision = gate.evaluate(pred, 0, false, 0.1f);
    assert(decision.action == camms::ArbiterDecision::Action::PRELOAD);

    // Tier 1 (relaxed GRU): 50% threshold
    pred.confidence = 0.45f;
    decision = gate.evaluate(pred, 1, false, 0.1f);
    assert(decision.action == camms::ArbiterDecision::Action::NONE);

    pred.confidence = 0.55f;
    decision = gate.evaluate(pred, 1, false, 0.1f);
    assert(decision.action == camms::ArbiterDecision::Action::PRELOAD);

    printf("[PASS] test_user_tier_thresholds\n");
    return 0;
}

int test_system_under_pressure() {
    camms::ConfidenceGate gate(0.6f, 0.5f);

    auto pred = make_prediction(42, 0.85f);
    auto decision = gate.evaluate(pred, 2, true, 0.1f);

    assert(decision.action == camms::ArbiterDecision::Action::DEFER);

    printf("[PASS] test_system_under_pressure\n");
    return 0;
}

int test_gru_disable() {
    camms::ConfidenceGate gate;

    // After 14 days, 500 predictions, only 5 above threshold (1%)
    assert(gate.should_disable_gru(15, 500, 5));

    // Day 5, shouldn't disable
    assert(!gate.should_disable_gru(5, 500, 5));

    // Good hit rate, shouldn't disable
    assert(!gate.should_disable_gru(15, 500, 100));

    printf("[PASS] test_gru_disable\n");
    return 0;
}

int main() {
    int failures = 0;
    failures += test_high_confidence_preload();
    failures += test_low_confidence_defer();
    failures += test_thermal_throttling();
    failures += test_user_tier_thresholds();
    failures += test_system_under_pressure();
    failures += test_gru_disable();

    printf("\n=== Confidence Gate: %d tests passed ===\n", 6 - failures);
    return failures;
}
