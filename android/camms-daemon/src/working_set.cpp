#include "working_set.h"
#include <algorithm>
#include <fstream>
#include <sstream>
#include <dirent.h>
#include <cctype>
#include <cstring>
#include <unistd.h>
#include <sys/types.h>

namespace camms {

WorkingSetMonitor::WorkingSetMonitor(size_t sample_window_ms)
    : sample_window_ms_(sample_window_ms) {}

std::vector<ProcessMemoryInfo> WorkingSetMonitor::scan_all() {
    std::vector<ProcessMemoryInfo> results;
    DIR* proc_dir = opendir("/proc");
    if (!proc_dir) return results;

    struct dirent* entry;
    while ((entry = readdir(proc_dir)) != nullptr) {
        bool is_num = true;
        for (const char* c = entry->d_name; *c; ++c) {
            if (!isdigit(*c)) { is_num = false; break; }
        }
        if (!is_num) continue;

        int32_t pid = std::atoi(entry->d_name);
        ProcessMemoryInfo info = read_process_smaps_rollup(pid);
        if (info.pss_kb > 0) {
            info.last_access_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            results.push_back(std::move(info));
        }
    }
    closedir(proc_dir);

    update(results);
    return results;
}

size_t WorkingSetMonitor::get_working_set(int32_t pid) const {
    auto it = last_scan_.find(pid);
    return it != last_scan_.end() ? it->second.pss_kb : 0;
}

bool WorkingSetMonitor::is_thrashing(double threshold) const {
    return current_refault_rate() > threshold;
}

double WorkingSetMonitor::current_refault_rate() const {
    uint64_t current = read_refault_count();
    uint64_t now_us = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    if (last_refault_count_ == 0) {
        last_refault_count_ = current;
        last_refault_read_us_ = now_us;
        return 0.0;
    }

    uint64_t elapsed_us = now_us - last_refault_read_us_;
    if (elapsed_us == 0) return 0.0;

    uint64_t delta = current - last_refault_count_;
    double rate = static_cast<double>(delta) / (elapsed_us / 1000000.0);

    last_refault_count_ = current;
    last_refault_read_us_ = now_us;

    return rate;
}

std::vector<int32_t> WorkingSetMonitor::compaction_candidates(size_t min_savings_kb) {
    std::vector<int32_t> candidates;

    for (const auto& [pid, info] : last_scan_) {
        if (!is_process_alive(pid)) continue;
        if (info.anon_kb > min_savings_kb) {
            candidates.push_back(pid);
        }
    }

    std::sort(candidates.begin(), candidates.end(),
        [this](int32_t a, int32_t b) {
            auto it_a = last_scan_.find(a);
            auto it_b = last_scan_.find(b);
            size_t a_size = it_a != last_scan_.end() ? it_a->second.anon_kb : 0;
            size_t b_size = it_b != last_scan_.end() ? it_b->second.anon_kb : 0;
            return a_size > b_size;
        });

    return candidates;
}

void WorkingSetMonitor::update(const std::vector<ProcessMemoryInfo>& snapshot) {
    size_t total_pss = 0;
    size_t total_rss = 0;
    size_t total_swap = 0;
    uint32_t active = 0;

    for (const auto& info : snapshot) {
        last_scan_[info.pid] = info;
        total_pss += info.pss_kb;
        total_rss += info.rss_kb;
        total_swap += info.swap_kb;
        if (info.pss_kb > 0) active++;
    }

    current_stats_.total_pss_kb = total_pss;
    current_stats_.total_rss_kb = total_rss;
    current_stats_.total_swap_kb = total_swap;
    current_stats_.active_processes = active;
    current_stats_.refault_rate = current_refault_rate();
}

ProcessMemoryInfo WorkingSetMonitor::read_process_smaps_rollup(int32_t pid) const {
    ProcessMemoryInfo info{};
    info.pid = pid;

    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    std::ifstream cmd(path);
    if (cmd.is_open()) {
        std::getline(cmd, info.name, '\0');
        cmd.close();
    }
    if (info.name.empty()) info.name = "<unknown>";

    snprintf(path, sizeof(path), "/proc/%d/smaps_rollup", pid);
    std::ifstream f(path);
    if (!f.is_open()) return info;

    std::string line;
    while (std::getline(f, line)) {
        char key[64];
        size_t value;
        if (sscanf(line.c_str(), "Pss: %zu kB", &value) == 1)
            info.pss_kb += value;
        else if (sscanf(line.c_str(), "Rss: %zu kB", &value) == 1)
            info.rss_kb += value;
        else if (sscanf(line.c_str(), "Swap: %zu kB", &value) == 1)
            info.swap_kb += value;
        else if (sscanf(line.c_str(), "Private_Dirty: %zu kB", &value) == 1)
            info.private_dirty_kb += value;
    }

    // Read VmSwap for anon pages estimate
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    std::ifstream status(path);
    if (status.is_open()) {
        while (std::getline(status, line)) {
            size_t value;
            if (sscanf(line.c_str(), "VmSwap: %zu kB", &value) == 1) {
                info.anon_kb = info.pss_kb + value;
            }
        }
    }

    return info;
}

uint64_t WorkingSetMonitor::read_refault_count() const {
    std::ifstream f("/proc/vmstat");
    if (!f.is_open()) return 0;

    std::string line;
    while (std::getline(f, line)) {
        uint64_t val;
        if (sscanf(line.c_str(), "workingset_refault %lu", &val) == 1) {
            return val;
        }
    }
    return 0;
}

bool WorkingSetMonitor::is_process_alive(int32_t pid) const {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    return access(path, F_OK) == 0;
}

} // namespace camms
