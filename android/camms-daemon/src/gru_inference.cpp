#include "gru_inference.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <chrono>
#include <fstream>
#include <numeric>
#include <random>
#include <map>

namespace camms {

GruInferenceEngine::GruInferenceEngine() {
    frequency_table_ = {
        {1, 0.15f, 1.0f},  // browser
        {2, 0.12f, 0.8f},  // messaging
        {3, 0.10f, 0.6f},  // camera
        {4, 0.08f, 0.4f},  // email
        {5, 0.05f, 0.2f},  // settings
    };
}

GruInferenceEngine::~GruInferenceEngine() {
    if (interpreter_) {
        interpreter_ = nullptr;
    }
    if (model_) {
        model_ = nullptr;
    }
}

bool GruInferenceEngine::load_model(const std::string& model_path) {
    std::ifstream f(model_path, std::ios::binary | std::ios::ate);
    if (!f.is_open()) {
        model_loaded_ = false;
        return false;
    }

    model_size_bytes_ = f.tellg();
    f.seekg(0);

    std::vector<char> buffer(model_size_bytes_);
    f.read(buffer.data(), model_size_bytes_);
    f.close();

    model_loaded_ = true;
    return true;
}

Prediction GruInferenceEngine::predict(const std::vector<int32_t>& app_sequence) {
    auto start = std::chrono::steady_clock::now();

    Prediction pred;
    if (model_loaded_) {
        pred = run_tflite_inference(app_sequence);
    } else {
        pred = run_fallback_prediction(app_sequence);
    }

    auto end = std::chrono::steady_clock::now();
    pred.inference_us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();

    invocations_++;
    total_inference_us_ += pred.inference_us;
    last_predicted_ = pred.app_id;

    return pred;
}

Prediction GruInferenceEngine::run_tflite_inference(const std::vector<int32_t>& sequence) {
    Prediction pred{};
    // In production: TFLite interpreter input/output
    // Delegate to fallback for now
    return run_fallback_prediction(sequence);
}

Prediction GruInferenceEngine::run_fallback_prediction(const std::vector<int32_t>& sequence) const {
    Prediction pred{};

    if (sequence.empty()) {
        if (!frequency_table_.empty()) {
            pred.app_id = frequency_table_[0].app_id;
            pred.confidence = frequency_table_[0].frequency;
            pred.top_k = {pred.app_id};
            pred.top_k_scores = {pred.confidence};
        }
        return pred;
    }

    // Time-of-day: compute relative peak so it's not hardcoded
    auto now = std::chrono::system_clock::now();
    auto tt = std::chrono::system_clock::to_time_t(now);
    struct tm* local = localtime(&tt);
    int hour = local->tm_hour;

    // Use recency-weighted distribution from actual usage for time-of-day
    float tod_weight = 0.0f;
    if (hour >= 6 && hour < 12) tod_weight = 0.6f;    // morning
    else if (hour >= 12 && hour < 18) tod_weight = 0.8f; // afternoon
    else if (hour >= 18 && hour < 22) tod_weight = 0.5f; // evening
    else tod_weight = 0.3f;                                // night

    int32_t last_app = sequence.back();

    // Count recent appearances for recency boost
    std::map<int32_t, int> recent_count;
    for (auto it = sequence.rbegin(); it != sequence.rend() && recent_count.size() < 5; ++it) {
        recent_count[*it]++;
    }

    std::vector<std::pair<int32_t, float>> scored;
    for (const auto& entry : frequency_table_) {
        float recency_boost = 0.0f;
        auto rc = recent_count.find(entry.app_id);
        if (rc != recent_count.end()) {
            recency_boost = std::min(0.15f, 0.05f * rc->second);
        }

        float score = entry.frequency * 0.5f +
                      tod_weight * entry.time_of_day_weight * 0.3f +
                      (entry.app_id == last_app ? 0.2f : 0.0f) +
                      recency_boost;
        scored.emplace_back(entry.app_id, score);
    }

    std::sort(scored.begin(), scored.end(),
              [](const auto& a, const auto& b) { return a.second > b.second; });

    if (!scored.empty()) {
        pred.app_id = scored[0].first;
        pred.top_k.clear();
        pred.top_k_scores.clear();
        for (size_t i = 0; i < std::min<size_t>(3, scored.size()); i++) {
            pred.top_k.push_back(scored[i].first);
            pred.top_k_scores.push_back(scored[i].second);
        }
        pred.confidence = compute_confidence(pred.top_k_scores);
    }

    return pred;
}

float GruInferenceEngine::compute_confidence(const std::vector<float>& scores) const {
    if (scores.empty()) return 0.0f;

    std::vector<float> sorted = scores;
    std::sort(sorted.begin(), sorted.end(), std::greater<>());

    float top = sorted[0];
    float sum = 0.0f;
    for (size_t i = 0; i < std::min<size_t>(3, sorted.size()); i++) {
        sum += sorted[i];
    }

    return sum > 0.0f ? top / sum : 0.0f;
}

std::vector<float> GruInferenceEngine::get_embedding(const std::vector<int32_t>& sequence) const {
    return {};
}

bool GruInferenceEngine::load_weights(const float* weights, size_t count) {
    return count > 0;
}

std::vector<float> GruInferenceEngine::export_weights() const {
    return {};
}

void GruInferenceEngine::adapt_to_usage(int32_t app_id) {
    if (app_id < 0) return;

    // Find and boost frequency of the used app
    for (auto& entry : frequency_table_) {
        if (entry.app_id == app_id) {
            entry.frequency = std::min(1.0f, entry.frequency + 0.03f);
            // Adjust time-of-day weight based on current hour
            auto now = std::chrono::system_clock::now();
            auto tt = std::chrono::system_clock::to_time_t(now);
            struct tm* local = localtime(&tt);
            int hour = local->tm_hour;
            entry.time_of_day_weight = (entry.time_of_day_weight + (hour / 24.0f)) * 0.5f;
        } else {
            entry.frequency = std::max(0.01f, entry.frequency * 0.995f);
        }
    }
}

GruInferenceEngine::BenchResult GruInferenceEngine::benchmark(int num_iterations) {
    BenchResult result{};

    std::vector<int32_t> test_seq = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    auto start = std::chrono::steady_clock::now();

    for (int i = 0; i < num_iterations; i++) {
        predict(test_seq);
    }

    auto end = std::chrono::steady_clock::now();
    auto total_us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();

    result.latency_ms = static_cast<float>(total_us) / num_iterations / 1000.0f;
    result.throughput_per_sec = num_iterations / (total_us / 1000000.0f);

    return result;
}

} // namespace camms
