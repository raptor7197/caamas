#ifndef CAMMS_PSI_MONITOR_H
#define CAMMS_PSI_MONITOR_H

#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <atomic>

namespace camms {

enum class PressureLevel : uint8_t {
    NONE = 0,
    SOME = 1,
    FULL = 2,
    CRITICAL = 3,
};

struct PsiSample {
    uint64_t timestamp_ms;
    PressureLevel level;
    double some_avg10;
    double some_avg60;
    double full_avg10;
    double full_avg60;
    uint64_t stall_us_some;
    uint64_t stall_us_full;
};

class PsiMonitor {
public:
    using PressureCallback = std::function<void(const PsiSample&)>;

    explicit PsiMonitor(int poll_interval_ms = 1000);
    ~PsiMonitor();

    bool start();
    void stop();
    bool running() const { return running_.load(); }

    PsiSample last_sample() const;
    PressureLevel current_pressure() const;

    void set_callback(PressureCallback cb) { callback_ = std::move(cb); }

private:
    std::string psi_path_{"/proc/pressure/memory"};
    int poll_interval_ms_;
    std::atomic<bool> running_{false};
    std::thread poll_thread_;
    PressureCallback callback_;
    mutable PsiSample last_sample_{};

    void poll_loop();
    PsiSample read_psi();
    PressureLevel classify_pressure(const PsiSample& sample) const;
};

} // namespace camms

#endif // CAMMS_PSI_MONITOR_H
