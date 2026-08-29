#include "psi_monitor.h"
#include <cstdio>
#include <cassert>
#include <chrono>
#include <thread>

int test_psi_parse() {
    camms::PsiMonitor monitor(500);
    auto sample = monitor.last_sample();
    assert(sample.timestamp_ms == 0); // Not started yet

    printf("[PASS] test_psi_parse (default construct)\n");
    return 0;
}

int test_pressure_classification() {
    camms::PsiMonitor monitor(500);

    // The monitor hasn't started, so we can't test live PSI
    // But we can verify the pressure level defaults to NONE
    assert(monitor.current_pressure() == camms::PressureLevel::NONE);

    printf("[PASS] test_pressure_classification (default NONE)\n");
    return 0;
}

int main() {
    int failures = 0;
    failures += test_psi_parse();
    failures += test_pressure_classification();

    printf("\n=== PSI Monitor: %d tests passed ===\n", 2 - failures);
    return failures;
}
