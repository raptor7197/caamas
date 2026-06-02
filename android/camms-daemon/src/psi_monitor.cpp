#include "psi_monitor.h"
#include <fstream>
#include <sstream>
#include <chrono>
#include <thread>
#include <cstdio>
#include <cstring>

namespace camms {

PsiMonitor::PsiMonitor(int poll_interval_ms)
    : poll_interval_ms_(poll_interval_ms) {}

PsiMonitor::~PsiMonitor() {
    stop();
}

bool PsiMonitor::start() {
    if (running_.load()) return true;
    running_.store(true);
    poll_thread_ = std::thread(&PsiMonitor::poll_loop, this);
    return true;
}

void PsiMonitor::stop() {
    running_.store(false);
    if (poll_thread_.joinable()) {
        poll_thread_.join();
    }
}

PsiSample PsiMonitor::last_sample() const {
    return last_sample_;
}

PressureLevel PsiMonitor::current_pressure() const {
    return last_sample_.level;
}

void PsiMonitor::poll_loop() {
    while (running_.load()) {
        PsiSample sample = read_psi();
        sample.level = classify_pressure(sample);
        last_sample_ = sample;

        if (callback_) {
            callback_(sample);
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(poll_interval_ms_));
    }
}

PsiSample PsiMonitor::read_psi() {
    PsiSample sample{};
    sample.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    std::ifstream f(psi_path_);
    if (!f.is_open()) {
        sample.level = PressureLevel::NONE;
        return sample;
    }

    std::string line;
    while (std::getline(f, line)) {
        double avg10 = 0, avg60 = 0, avg300 = 0;
        uint64_t total = 0;
        if (sscanf(line.c_str(), "some avg10=%lf avg60=%lf avg300=%lf total=%lu",
                   &avg10, &avg60, &avg300, &total) == 4) {
            sample.some_avg10 = avg10;
            sample.some_avg60 = avg60;
            sample.stall_us_some = total;
        } else if (sscanf(line.c_str(), "full avg10=%lf avg60=%lf avg300=%lf total=%lu",
                          &avg10, &avg60, &avg300, &total) == 4) {
            sample.full_avg10 = avg10;
            sample.full_avg60 = avg60;
            sample.stall_us_full = total;
        }
    }

    return sample;
}

PressureLevel PsiMonitor::classify_pressure(const PsiSample& sample) const {
    if (sample.full_avg10 > 10.0) return PressureLevel::CRITICAL;
    if (sample.full_avg10 > 3.0) return PressureLevel::FULL;
    if (sample.some_avg10 > 5.0) return PressureLevel::SOME;
    return PressureLevel::NONE;
}

} // namespace camms
