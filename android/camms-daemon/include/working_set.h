#ifndef CAMMS_WORKING_SET_H
#define CAMMS_WORKING_SET_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>
#include <chrono>

namespace camms {

struct ProcessMemoryInfo {
    int32_t pid;
    std::string name;
    size_t pss_kb;        // proportional set size
    size_t rss_kb;        // resident set size
    size_t swap_kb;       // swapped out
    size_t private_dirty_kb;
    size_t anon_kb;       // anonymous pages
    size_t file_kb;       // file-backed pages
    uint64_t last_access_ms;
    uint32_t page_fault_delta;
};

class WorkingSetMonitor {
public:
    explicit WorkingSetMonitor(size_t sample_window_ms = 5000);

    // Scan all processes and return current memory snapshot
    std::vector<ProcessMemoryInfo> scan_all();

    // Get working set estimate for a specific app
    size_t get_working_set(int32_t pid) const;

    // Detect thrashing: returns true if refault rate exceeds threshold
    bool is_thrashing(double threshold = 0.3) const;

    // Get memory pressure signal from PSI
    double current_refault_rate() const;

    // Find compaction candidates (processes with large inactive working sets)
    std::vector<int32_t> compaction_candidates(size_t min_savings_kb);

    // Update working set tracking with new scan data
    void update(const std::vector<ProcessMemoryInfo>& snapshot);

    struct WorkingSetStats {
        size_t total_pss_kb{0};
        size_t total_rss_kb{0};
        size_t total_swap_kb{0};
        double refault_rate{0.0};
        uint32_t active_processes{0};
    };
    WorkingSetStats stats() const { return current_stats_; }

private:
    size_t sample_window_ms_;
    mutable WorkingSetStats current_stats_{};
    std::unordered_map<int32_t, ProcessMemoryInfo> last_scan_;
    mutable uint64_t last_refault_read_us_{0};
    mutable uint64_t last_refault_count_{0};

    ProcessMemoryInfo read_process_smaps_rollup(int32_t pid) const;
    uint64_t read_refault_count() const;
    bool is_process_alive(int32_t pid) const;
};

} // namespace camms

#endif // CAMMS_WORKING_SET_H
