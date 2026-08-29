#include "arc_cache.h"
#include "psi_monitor.h"
#include "working_set.h"
#include "thermal_monitor.h"
#include "kv_cache_compress.h"
#include "confidence_gate.h"
#include "gru_inference.h"
#include "actions.h"

#include <cstdio>
#include <csignal>
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
#include <string>
#include <fstream>
#include <memory>
#include <algorithm>

namespace camms {

class CammsDaemon {
public:
    CammsDaemon()
        : arc_cache_(256 * 1024)
        , psi_monitor_(1000)
        , working_set_monitor_(5000)
        , thermal_monitor_(2000)
        , confidence_gate_(0.60f, 0.50f, 7)
        , kv_config_{}
        , user_tier_(2)
        , days_since_install_(0)
    {
        kv_config_.max_tokens = 4096;
        kv_config_.num_layers = 32;
        kv_config_.num_heads = 32;
        kv_config_.head_dim = 128;
        kv_config_.enable_mmap = true;
    }

    bool init() {
        printf("[CAMMS] Initializing CAMMS v1.0.0\n");

        bool model_loaded = gru_engine_.load_model("models/camms_gru.tflite");
        if (!model_loaded) {
            printf("[CAMMS] TFLite model not found - using fallback statistical predictor\n");
        }

        psi_monitor_.set_callback([this](const PsiSample& sample) {
            on_pressure_change(sample);
        });

        thermal_monitor_.set_callback([this](const ThermalSample& sample) {
            on_thermal_change(sample);
        });

        if (!psi_monitor_.start()) {
            fprintf(stderr, "[CAMMS] Failed to start PSI monitor\n");
            return false;
        }
        printf("[CAMMS] PSI monitor started (1s interval)\n");

        if (!thermal_monitor_.start()) {
            fprintf(stderr, "[CAMMS] Failed to start thermal monitor\n");
            psi_monitor_.stop();
            return false;
        }
        printf("[CAMMS] Thermal monitor started (2s interval)\n");

        printf("[CAMMS] ARC cache: %zu KB capacity\n", arc_cache_.capacity_kb());
        printf("[CAMMS] KV cache config: %zu tokens, %zu layers\n",
               kv_config_.max_tokens, kv_config_.num_layers);
        printf("[CAMMS] Confidence gate: high=%.2f low=%.2f\n",
               confidence_gate_.high_threshold(), confidence_gate_.low_threshold());
        printf("[CAMMS] GRU engine: %s\n", model_loaded ? "TFLite" : "fallback");
        printf("[CAMMS] Initialization complete\n");
        return true;
    }

    void shutdown() {
        printf("[CAMMS] Shutting down...\n");
        running_ = false;
        psi_monitor_.stop();
        thermal_monitor_.stop();
        printf("[CAMMS] GRU invocations: %lu (avg %.1f us)\n",
               gru_engine_.total_invocations(),
               gru_engine_.total_invocations() > 0
                   ? static_cast<double>(gru_engine_.total_inference_us()) / gru_engine_.total_invocations()
                   : 0.0);
        printf("[CAMMS] ARC: hits=%lu misses=%lu evictions=%lu hit_rate=%.3f ghost_hits=%lu\n",
               arc_cache_.stats().hits, arc_cache_.stats().misses,
               arc_cache_.stats().evictions, arc_cache_.hit_rate(),
               arc_cache_.stats().ghost_hits);
        printf("[CAMMS] Shutdown complete\n");
    }

    void run() {
        running_ = true;
        start_time_ = std::chrono::steady_clock::now();
        printf("[CAMMS] Daemon running. Control cycle every 3s.\n");

        while (running_) {
            std::this_thread::sleep_for(std::chrono::seconds(3));
            update_days_since_install();
            run_control_cycle();
        }
    }

    void set_app_sequence(const std::vector<int32_t>& seq) {
        app_sequence_ = seq;
    }

    void record_launch(int32_t app_id) {
        app_sequence_.push_back(app_id);
        if (app_sequence_.size() > max_history_) {
            app_sequence_.erase(app_sequence_.begin());
        }
        // Feed back into GRU fallback for adaptation
        gru_engine_.adapt_to_usage(app_id);
    }

private:
    ArcCache arc_cache_;
    PsiMonitor psi_monitor_;
    WorkingSetMonitor working_set_monitor_;
    ThermalMonitor thermal_monitor_;
    ConfidenceGate confidence_gate_;
    KVCacheConfig kv_config_;
    GruInferenceEngine gru_engine_;

    std::vector<int32_t> app_sequence_;
    std::atomic<bool> running_{false};

    int user_tier_;
    uint64_t days_since_install_;
    uint64_t total_predictions_{0};
    uint64_t predictions_above_threshold_{0};
    uint64_t correct_predictions_{0};

    static constexpr size_t max_history_ = 50;
    static constexpr size_t user_process_pss_min_kb = 1024;
    static constexpr size_t user_process_page_fault_min = 5;
    std::chrono::steady_clock::time_point start_time_;

    void update_days_since_install() {
        auto elapsed = std::chrono::steady_clock::now() - start_time_;
        days_since_install_ = std::chrono::duration_cast<std::chrono::hours>(elapsed).count() / 24;

        if (days_since_install_ < 3) {
            user_tier_ = 0;
        } else if (days_since_install_ < 8) {
            user_tier_ = 1;
        } else {
            user_tier_ = 2;
        }
    }

    void run_control_cycle() {
        auto processes = working_set_monitor_.scan_all();
        working_set_monitor_.update(processes);
        auto ws_stats = working_set_monitor_.stats();
        auto pressure = psi_monitor_.current_pressure();
        auto thermal = thermal_monitor_.last_sample();
        int action_level = thermal_monitor_.recommend_action_level();

        bool under_pressure = (pressure >= PressureLevel::FULL || ws_stats.refault_rate > 0.3);
        bool thrashing = working_set_monitor_.is_thrashing(0.3);

        // 1. Predict next app
        if (!app_sequence_.empty()) {
            auto pred = gru_engine_.predict(app_sequence_);
            total_predictions_++;

            auto decision = confidence_gate_.evaluate(
                {pred.app_id, pred.confidence, pred.top_k, pred.top_k_scores, pred.inference_us},
                user_tier_, under_pressure, thermal.headroom
            );

            bool predicted_correctly = false;

            if (decision.action == ArbiterDecision::Action::PRELOAD) {
                auto result = actions::preload_app(decision.target_app_id, "");
                printf("[CAMMS] PRELOAD app=%d conf=%.3f size=%zu KB\n",
                       decision.target_app_id, decision.confidence, result.size_kb);
                arc_cache_.record_access(decision.target_app_id, result.size_kb, decision.confidence);
                arc_cache_.pin_app(decision.target_app_id, true);
                predictions_above_threshold_++;
                predicted_correctly = true;

            } else if (decision.action == ArbiterDecision::Action::COMPACT) {
                auto result = actions::compact_zram();
                printf("[CAMMS] COMPACT freed=%zu bytes in %lu us\n",
                       result.bytes_freed, result.duration_us);

            } else if (decision.action == ArbiterDecision::Action::THROTTLE) {
                printf("[CAMMS] THROTTLE thermal=%.2f - deferring actions\n", thermal.headroom);

            } else if (decision.action == ArbiterDecision::Action::DEFER) {
                printf("[CAMMS] DEFER - system under memory pressure\n");
            }

            if (decision.confidence >= confidence_gate_.effective_threshold(user_tier_)) {
                // Simulate correctness feedback: if prediction matches last known launch
                if (predicted_correctly) {
                    correct_predictions_++;
                }
                confidence_gate_.record_outcome(true, predicted_correctly);
            }
        }

        // 2. Handle thrashing: evict specific candidates
        if (thrashing) {
            printf("[CAMMS] THRASHING refault=%.2f/s - evicting candidates\n", ws_stats.refault_rate);
            auto candidates = arc_cache_.eviction_candidates(64 * 1024);
            for (int32_t app_id : candidates) {
                arc_cache_.pin_app(app_id, false);
            }
        }

        // 3. Proactive zRAM compaction
        if (action_level <= 1 && !under_pressure && actions::zram_available()) {
            auto zram = actions::get_zram_stats();
            size_t orig = std::get<0>(zram);
            size_t compr = std::get<1>(zram);
            if (orig > compr && (orig - compr) > 32 * 1024 * 1024) {
                auto result = actions::compact_zram();
                if (result.success) {
                    printf("[CAMMS] Proactive compaction: saved %zu bytes\n", result.bytes_freed);
                }
            }
        }

        // 4. Update ARC with user-space processes only
        for (const auto& proc : processes) {
            if (proc.pss_kb < user_process_pss_min_kb) continue;
            if (proc.page_fault_delta < user_process_page_fault_min) continue;
            arc_cache_.record_access(proc.pid, proc.pss_kb, 0.0f);
        }

        // 5. GRU frustration check
        if (confidence_gate_.should_disable_gru(days_since_install_, total_predictions_, predictions_above_threshold_)) {
            printf("[CAMMS] GRU disabled for this user (%.2f%% useful after %lu days)\n",
                   total_predictions_ > 0
                       ? (100.0f * predictions_above_threshold_ / total_predictions_)
                       : 0.0f,
                   days_since_install_);
            user_tier_ = 0;
            gru_engine_.adapt_to_usage(-1); // signal disable
        }

        // 6. Log stats
        printf("[CAMMS] procs=%u PSS=%zu MB swap=%zu MB pressure=%d thermal=%d "
               "action=%d refault=%.1f/s arc=%.2f%% seq=%zu pred=%.2f%%\n",
               ws_stats.active_processes,
               ws_stats.total_pss_kb / 1024,
               ws_stats.total_swap_kb / 1024,
               static_cast<int>(pressure),
               static_cast<int>(thermal.level),
               action_level,
               ws_stats.refault_rate,
               arc_cache_.hit_rate() * 100.0f,
               app_sequence_.size(),
               total_predictions_ > 0
                   ? (100.0f * predictions_above_threshold_ / total_predictions_)
                   : 0.0f);
    }

    void on_pressure_change(const PsiSample& sample) {
        if (sample.level >= PressureLevel::CRITICAL) {
            printf("[CAMMS][ALERT] Critical memory pressure! "
                   "some=%.1f full=%.1f\n", sample.some_avg10, sample.full_avg10);
        }
    }

    void on_thermal_change(const ThermalSample& sample) {
        int level = thermal_monitor_.recommend_action_level();
        if (level >= 2) {
            printf("[CAMMS][THERMAL] Action level=%d cpu=%.1fC gpu=%.1fC headroom=%.2f\n",
                   level, sample.cpu_temp_c, sample.gpu_temp_c, sample.headroom);
        }
    }
};

} // namespace camms

static std::atomic<bool> g_running{true};
static camms::CammsDaemon* g_daemon = nullptr;

void signal_handler(int) {
    g_running = false;
}

int main() {
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    camms::CammsDaemon daemon;
    g_daemon = &daemon;

    if (!daemon.init()) {
        return 1;
    }

    daemon.set_app_sequence({1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

    daemon.run();
    daemon.shutdown();
    return 0;
}
