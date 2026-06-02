#include "actions.h"
#include <cstdio>
#include <cstring>
#include <chrono>
#include <fstream>
#include <sstream>
#include <string>
#include <thread>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

namespace camms::actions {

PreloadAction preload_app(int32_t app_id, const std::string& package_name) {
    PreloadAction action{};
    action.app_id = app_id;
    action.package_name = package_name;

    auto start = std::chrono::steady_clock::now();

    // In production: mincore() + readahead() on mapped APK/ODEX segments
    // Placeholder: 4 MB simulated preload
    std::this_thread::sleep_for(std::chrono::microseconds(100));
    auto end = std::chrono::steady_clock::now();

    action.size_kb = 4096;
    action.success = true;
    return action;
}

CompactionResult compact_zram() {
    CompactionResult result{};
    auto start = std::chrono::steady_clock::now();

    FILE* f = fopen("/sys/block/zram0/compact", "w");
    if (f) {
        fprintf(f, "1");
        fclose(f);

        std::ifstream mm_stat("/sys/block/zram0/mm_stat");
        if (mm_stat.is_open()) {
            size_t orig = 0, compr = 0, mem_used = 0;
            mm_stat >> orig >> compr >> mem_used;
            result.bytes_freed = orig > compr ? orig - compr : 0;
        }
        result.success = true;
    } else {
        result.success = false;
    }

    auto end = std::chrono::steady_clock::now();
    result.duration_us = std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
    return result;
}

bool drop_process_cache(int32_t pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/clear_refs", pid);
    FILE* f = fopen(path, "w");
    if (!f) return false;
    fprintf(f, "1");
    fclose(f);
    return true;
}

std::tuple<size_t, size_t, size_t> get_zram_stats() {
    std::ifstream f("/sys/block/zram0/mm_stat");
    if (!f.is_open()) return {0, 0, 0};

    size_t orig = 0, compr = 0, mem_used = 0;
    f >> orig >> compr >> mem_used;
    return {orig, compr, mem_used};
}

bool zram_available() {
    return access("/sys/block/zram0", F_OK) == 0;
}

size_t readahead_pages(const std::string& path, size_t length) {
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return 0;

    posix_fadvise(fd, 0, length, POSIX_FADV_WILLNEED);
    size_t pages_read = length / 4096;
    close(fd);
    return pages_read;
}

bool mlock_process(int32_t pid) {
    // mlock across process boundaries requires ptrace + process_vm_readv
    (void)pid;
    return false; // not implemented
}

bool munlock_process(int32_t pid) {
    (void)pid;
    return false; // not implemented
}

} // namespace camms::actions
