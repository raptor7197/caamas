#include "confidence_gate.h"
#include <algorithm>
#include <cmath>

namespace camms {

ConfidenceGate::ConfidenceGate(float high_threshold, float low_threshold, int tier_transition_days)
    : high_threshold_(high_threshold)
    , low_threshold_(low_threshold)
    , tier_transition_days_(tier_transition_days)
{}

ArbiterDecision ConfidenceGate::evaluate(
    const PredictionResult& prediction,
    int user_tier,
    bool system_under_pressure,
    float thermal_headroom
) {
    total_decisions_++;
    float gate = effective_threshold(user_tier);
    ArbiterDecision decision{};
    decision.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    decision.confidence = prediction.confidence;

    // Thermal override: headroom > 0.85 means near-shutdown (Android severity scale)
    if (thermal_headroom > 0.85 && user_tier >= 2) {
        decision.action = ArbiterDecision::Action::THROTTLE;
        decision.reason = "Thermal throttling";
        return decision;
    }

    if (system_under_pressure && user_tier >= 2) {
        decision.action = ArbiterDecision::Action::DEFER;
        decision.reason = "System under memory pressure";
        return decision;
    }

    // Confidence gating
    if (prediction.confidence >= gate) {
        decisions_above_threshold_++;
        return make_preload_decision(prediction, gate, system_under_pressure, thermal_headroom);
    }

    decision.action = ArbiterDecision::Action::NONE;
    decision.reason = "Confidence below threshold";
    decision.target_app_id = -1;
    return decision;
}

float ConfidenceGate::effective_threshold(int user_tier) const {
    switch (user_tier) {
        case 0: return 0.0f;    // Markov chain: always predict
        case 1: return low_threshold_;  // Relaxed GRU
        case 2: return high_threshold_; // Full GRU
        default: return high_threshold_;
    }
}

bool ConfidenceGate::should_disable_gru(
    uint64_t days_since_install,
    uint32_t total_predictions,
    uint32_t times_above_threshold
) const {
    if (days_since_install < static_cast<uint64_t>(tier_transition_days_ * 2)) return false;
    if (total_predictions < 100) return false;
    float hit_ratio = static_cast<float>(times_above_threshold) /
                      std::max(1.0f, static_cast<float>(total_predictions));
    return hit_ratio < 0.05f;
}

void ConfidenceGate::record_outcome(bool was_above_threshold, bool was_correct) {
    if (was_correct) {
        correct_predictions_++;
    }

    // Adaptive threshold tuning: slowly adjust based on prediction success rate
    // If >70% of above-threshold predictions are correct, raise threshold
    // If <30% are correct, lower threshold
    if (total_decisions_ < 50) return; // warm-up period

    float accuracy = decisions_above_threshold_ > 0
        ? static_cast<float>(correct_predictions_) / decisions_above_threshold_
        : 0.0f;

    if (accuracy > 0.7f && high_threshold_ < 0.95f) {
        high_threshold_ = std::min(0.95f, high_threshold_ + 0.02f);
    } else if (accuracy < 0.3f && high_threshold_ > 0.1f) {
        high_threshold_ = std::max(0.1f, high_threshold_ - 0.02f);
    }
    // Keep low threshold ≤ high threshold
    low_threshold_ = std::min(low_threshold_, high_threshold_);
}

void ConfidenceGate::set_thresholds(float high, float low) {
    high_threshold_ = std::clamp(high, 0.0f, 1.0f);
    low_threshold_ = std::clamp(low, 0.0f, high_threshold_);
}

ArbiterDecision ConfidenceGate::make_preload_decision(
    const PredictionResult& prediction,
    float effective_gate,
    bool under_pressure,
    float thermal_headroom
) const {
    ArbiterDecision decision{};
    decision.confidence = prediction.confidence;
    decision.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    // Thermal scaling: headroom > 0.60 means HOT → reduced preload
    if (thermal_headroom > 0.60) {
        decision.action = ArbiterDecision::Action::PRELOAD;
        decision.target_app_id = prediction.predicted_app_id;
        decision.reason = "Preload (thermal-aware, reduced)";
        return decision;
    }

    if (under_pressure) {
        decision.action = ArbiterDecision::Action::COMPACT;
        decision.target_app_id = prediction.predicted_app_id;
        decision.reason = "Compact before preload (under pressure)";
        return decision;
    }

    decision.action = ArbiterDecision::Action::PRELOAD;
    decision.target_app_id = prediction.predicted_app_id;
    decision.reason = "Preload (full)";
    return decision;
}

} // namespace camms
