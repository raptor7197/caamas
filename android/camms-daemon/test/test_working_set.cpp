#include "working_set.h"
#include <cstdio>
#include <cassert>
#include <unistd.h>

int test_scan_all() {
    camms::WorkingSetMonitor monitor(5000);
    auto processes = monitor.scan_all();

    // Should find at least this process
    assert(!processes.empty());

    bool found_self = false;
    for (const auto& proc : processes) {
        if (proc.pid == getpid()) {
            found_self = true;
            assert(proc.pss_kb > 0);
            printf("[INFO] Self: PID=%d PSS=%zu KB RSS=%zu KB name=%s\n",
                   proc.pid, proc.pss_kb, proc.rss_kb, proc.name.c_str());
            break;
        }
    }
    assert(found_self);

    printf("[PASS] test_scan_all (%zu processes found)\n", processes.size());
    return 0;
}

int test_refault_rate() {
    camms::WorkingSetMonitor monitor(5000);
    double rate = monitor.current_refault_rate();

    // Rate should be a non-negative number
    assert(rate >= 0.0);

    printf("[PASS] test_refault_rate (%.2f/s)\n", rate);
    return 0;
}

int test_thrashing_detection() {
    camms::WorkingSetMonitor monitor(5000);

    // Initially should not be thrashing
    bool thrashing = monitor.is_thrashing(0.3);
    assert(!thrashing); // System shouldn't be thrashing at idle

    printf("[PASS] test_thrashing_detection\n");
    return 0;
}

int test_compaction_candidates() {
    camms::WorkingSetMonitor monitor(5000);
    monitor.scan_all(); // Populate state

    auto candidates = monitor.compaction_candidates(1024); // 1MB minimum

    // Should return some candidates or at least not crash
    printf("[INFO] Compaction candidates: %zu\n", candidates.size());

    printf("[PASS] test_compaction_candidates\n");
    return 0;
}

int main() {
    int failures = 0;
    failures += test_scan_all();
    failures += test_refault_rate();
    failures += test_thrashing_detection();
    failures += test_compaction_candidates();

    printf("\n=== Working Set Monitor: %d tests passed ===\n", 4 - failures);
    return failures;
}
