#ifndef CAMMS_ACTIONS_H
#define CAMMS_ACTIONS_H

#include <cstdint>
#include <string>
#include <vector>

namespace camms {

struct PreloadAction {
    int32_t app_id;
    std::string package_name;
    size_t size_kb;
    bool success;
};

struct CompactionResult {
    size_t bytes_freed;
    size_t duration_us;
    bool success;
};

namespace actions {

// Preload app pages via readahead
PreloadAction preload_app(int32_t app_id, const std::string& package_name);

// Trigger zRAM compaction
CompactionResult compact_zram();

// Drop caches for a specific process (echo 3 > /proc/sys/vm/drop_caches analog)
bool drop_process_cache(int32_t pid);

// Get zRAM stats: returns (orig_data_size, compr_data_size, mem_used_total)
std::tuple<size_t, size_t, size_t> get_zram_stats();

// Check if zRAM device exists
bool zram_available();

// Readahead file pages into cache
size_t readahead_pages(const std::string& path, size_t length);

// mlock critical process pages
bool mlock_process(int32_t pid);

// munlock process pages
bool munlock_process(int32_t pid);

} // namespace actions
} // namespace camms

#endif // CAMMS_ACTIONS_H
