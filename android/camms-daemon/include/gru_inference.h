#ifndef CAMMS_GRU_INFERENCE_H
#define CAMMS_GRU_INFERENCE_H

#include <cstdint>
#include <string>
#include <vector>

namespace camms {

struct Prediction {
    int32_t app_id;
    float confidence;
    std::vector<int32_t> top_k;
    std::vector<float> top_k_scores;
    uint64_t inference_us;
};

class GruInferenceEngine {
public:
    explicit GruInferenceEngine();
    ~GruInferenceEngine();

    bool load_model(const std::string& model_path);
    bool is_loaded() const { return model_loaded_; }

    Prediction predict(const std::vector<int32_t>& app_sequence);
    void adapt_to_usage(int32_t app_id);
    std::vector<float> get_embedding(const std::vector<int32_t>& app_sequence) const;

    bool load_weights(const float* weights, size_t count);
    std::vector<float> export_weights() const;

    size_t model_size_bytes() const { return model_size_bytes_; }
    uint64_t total_invocations() const { return invocations_; }
    uint64_t total_inference_us() const { return total_inference_us_; }

    struct BenchResult {
        float latency_ms;
        float throughput_per_sec;
        float top1_accuracy;
        float top3_accuracy;
    };
    BenchResult benchmark(int num_iterations = 100);

private:
    bool model_loaded_{false};
    size_t model_size_bytes_{0};
    uint64_t invocations_{0};
    uint64_t total_inference_us_{0};

    // TFLite interpreter handles (loaded at runtime)
    void* interpreter_{nullptr};
    void* model_{nullptr};

    // Fallback: lightweight statistical predictor when TFLite unavailable
    struct FrequencyEntry {
        int32_t app_id;
        float frequency;
        float time_of_day_weight;
    };
    std::vector<FrequencyEntry> frequency_table_;
    int32_t last_predicted_{-1};

    bool load_model_file(const std::string& path);
    Prediction run_tflite_inference(const std::vector<int32_t>& sequence);
    Prediction run_fallback_prediction(const std::vector<int32_t>& sequence) const;
    float compute_confidence(const std::vector<float>& scores) const;
};

} // namespace camms

#endif // CAMMS_GRU_INFERENCE_H
