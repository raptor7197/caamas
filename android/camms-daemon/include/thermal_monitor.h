#ifndef CAMMS_THERMAL_MONITOR_H
#define CAMMS_THERMAL_MONITOR_H

#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <atomic>

namespace camms {

enum class ThermalLevel : uint8_t {
    COOL = 0,
    WARM = 1,
    HOT = 2,
    CRITICAL = 3,
};

struct ThermalSample {
    uint64_t timestamp_ms;
    ThermalLevel level;
    double headroom;  // AThermal_getThermalHeadroom style [0, 1]
    double cpu_temp_c;
    double gpu_temp_c;
    double battery_temp_c;
};

class ThermalMonitor {
public:
    using ThermalCallback = std::function<void(const ThermalSample&)>;

    explicit ThermalMonitor(int poll_interval_ms = 2000);
    ~ThermalMonitor();

    bool start();
    void stop();
    bool running() const { return running_.load(); }

    ThermalLevel current_level() const;
    double headroom() const;
    ThermalSample last_sample() const;

    void set_callback(ThermalCallback cb) { callback_ = std::move(cb); }

    // Predictive thermal scaling: returns recommended action level [0, 3]
    // 0 = full performance, 1 = scale down, 2 = minimal, 3 = halt
    int recommend_action_level() const;

private:
    int poll_interval_ms_;
    std::atomic<bool> running_{false};
    std::thread poll_thread_;
    ThermalCallback callback_;
    mutable ThermalSample last_sample_{};

    void poll_loop();
    ThermalSample read_thermal_state();
    double read_sysfs_temp(const std::string& base_path) const;
    ThermalLevel classify_thermal(double headroom, double cpu_temp) const;
};

} // namespace camms

#endif // CAMMS_THERMAL_MONITOR_H
