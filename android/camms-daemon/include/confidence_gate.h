#ifndef CAMMS_CONFIDENCE_GATE_H
#define CAMMS_CONFIDENCE_GATE_H

#include <cstdint>
#include <vector>
#include <string>
#include <chrono>

namespace camms {

struct PredictionResult {
    int32_t predicted_app_id;
    float confidence;
    std::vector<int32_t> top_k;      // top-3 predicted apps
    std::vector<float> top_k_scores;
    uint64_t inference_time_us;
};

struct ArbiterDecision {
    enum class Action : uint8_t {
        NONE = 0,
        PRELOAD = 1,
        COMPACT = 2,
        EVICT = 3,
        DEFER = 4,
        THROTTLE = 5,
    };

    Action action;
    int32_t target_app_id;
    std::string reason;
    uint64_t timestamp_ms;
    float confidence;
};

class ConfidenceGate {
public:
    explicit ConfidenceGate(
        float high_threshold = 0.60f,
        float low_threshold = 0.50f,
        int tier_transition_days = 7
    );

    // Evaluate a prediction and decide what action to take
    ArbiterDecision evaluate(
        const PredictionResult& prediction,
        int user_tier,           // 0=Markov, 1=relaxed GRU, 2=full GRU
        bool system_under_pressure,
        float thermal_headroom
    );

    // Get the effective confidence threshold for current user tier
    float effective_threshold(int user_tier) const;

    // Check if GRU should be disabled for this user (model frustration)
    bool should_disable_gru(uint64_t days_since_install, uint32_t total_predictions, uint32_t times_above_threshold) const;

    // Adaptive threshold tuning
    void record_outcome(bool was_above_threshold, bool was_correct);

    void set_thresholds(float high, float low);
    float high_threshold() const { return high_threshold_; }
    float low_threshold() const { return low_threshold_; }

private:
    float high_threshold_;
    float low_threshold_;
    int tier_transition_days_;

    uint64_t total_decisions_{0};
    uint64_t decisions_above_threshold_{0};
    uint64_t correct_predictions_{0};

    ArbiterDecision make_preload_decision(
        const PredictionResult& prediction,
        float effective_gate,
        bool under_pressure,
        float thermal_headroom
    ) const;
};

} // namespace camms

#endif // CAMMS_CONFIDENCE_GATE_H
