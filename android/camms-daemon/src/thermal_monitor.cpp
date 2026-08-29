#include "thermal_monitor.h"
#include <algorithm>
#include <fstream>
#include <sstream>
#include <chrono>
#include <thread>
#include <dirent.h>
#include <cstring>
#include <cstdlib>

namespace camms {

ThermalMonitor::ThermalMonitor(int poll_interval_ms)
    : poll_interval_ms_(poll_interval_ms) {}

ThermalMonitor::~ThermalMonitor() {
    stop();
}

bool ThermalMonitor::start() {
    if (running_.load()) return true;
    running_.store(true);
    poll_thread_ = std::thread(&ThermalMonitor::poll_loop, this);
    return true;
}

void ThermalMonitor::stop() {
    running_.store(false);
    if (poll_thread_.joinable()) {
        poll_thread_.join();
    }
}

ThermalLevel ThermalMonitor::current_level() const {
    return last_sample_.level;
}

double ThermalMonitor::headroom() const {
    return last_sample_.headroom;
}

ThermalSample ThermalMonitor::last_sample() const {
    return last_sample_;
}

void ThermalMonitor::poll_loop() {
    while (running_.load()) {
        ThermalSample sample = read_thermal_state();
        sample.level = classify_thermal(sample.headroom, sample.cpu_temp_c);
        last_sample_ = sample;

        if (callback_) {
            callback_(sample);
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(poll_interval_ms_));
    }
}

ThermalSample ThermalMonitor::read_thermal_state() {
    ThermalSample sample{};
    sample.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    // Simulate headroom: in production this calls AThermal_getThermalHeadroom()
    // For now, read from sysfs and compute a synthetic headroom
    sample.cpu_temp_c = read_sysfs_temp("/sys/class/thermal/thermal_zone0/temp");
    sample.gpu_temp_c = read_sysfs_temp("/sys/class/thermal/thermal_zone1/temp");
    sample.battery_temp_c = read_sysfs_temp("/sys/class/power_supply/battery/temp");

    // Compute headroom: 0 = cool, 1 = critical
    // Normalize: 30C -> 0.0, 90C -> 1.0
    double max_temp = std::max({sample.cpu_temp_c, sample.gpu_temp_c, sample.battery_temp_c});
    sample.headroom = std::clamp((max_temp - 30.0) / 60.0, 0.0, 1.0);

    return sample;
}

double ThermalMonitor::read_sysfs_temp(const std::string& base_path) const {
    std::ifstream f(base_path);
    if (!f.is_open()) return 25.0; // default room temp

    int millidegrees = 0;
    f >> millidegrees;
    return millidegrees / 1000.0;
}

ThermalLevel ThermalMonitor::classify_thermal(double headroom, double cpu_temp) const {
    if (headroom > 0.85 || cpu_temp > 85.0) return ThermalLevel::CRITICAL;
    if (headroom > 0.60 || cpu_temp > 70.0) return ThermalLevel::HOT;
    if (headroom > 0.30 || cpu_temp > 55.0) return ThermalLevel::WARM;
    return ThermalLevel::COOL;
}

int ThermalMonitor::recommend_action_level() const {
    auto level = last_sample_.level;
    double hr = last_sample_.headroom;

    switch (level) {
        case ThermalLevel::COOL:
            return 0; // Full performance
        case ThermalLevel::WARM:
            return 1; // Scale down preloading to 50%
        case ThermalLevel::HOT:
            return 2; // Minimal operations
        case ThermalLevel::CRITICAL:
            return 3; // Halt non-essential
    }
    return 0;
}

} // namespace camms
