#include "arc_cache.h"
#include <cstdio>
#include <cassert>
#include <cstdlib>

int test_basic_insert_and_access() {
    camms::ArcCache cache(10 * 1024); // 10 MB

    cache.record_access(1, 1024);
    assert(cache.current_usage_kb() > 0);
    assert(cache.stats().hits == 0);
    assert(cache.stats().misses == 1);

    cache.record_access(1, 1024);
    assert(cache.stats().hits == 1);

    printf("[PASS] test_basic_insert_and_access\n");
    return 0;
}

int test_eviction() {
    camms::ArcCache cache(5 * 1024); // 5 MB

    // Insert 10 apps at 1MB each
    for (int i = 0; i < 10; i++) {
        cache.record_access(i + 1, 1024);
    }

    assert(cache.current_usage_kb() <= cache.capacity_kb());
    assert(cache.stats().evictions > 0);
    assert(cache.hit_rate() >= 0.0f);

    printf("[PASS] test_eviction (evictions=%lu)\n", cache.stats().evictions);
    return 0;
}

int test_pin_app() {
    camms::ArcCache cache(3 * 1024); // 3 MB

    // Insert two pinned apps
    for (int i = 0; i < 3; i++) {
        cache.record_access(i + 1, 1024);
    }
    cache.pin_app(1, true);
    cache.pin_app(2, true);

    // Insert more to force eviction
    for (int i = 0; i < 10; i++) {
        cache.record_access(i + 10, 1024);
    }

    // Pinned apps should not be evicted
    auto candidates = cache.eviction_candidates(1024);
    for (int32_t app : candidates) {
        assert(app != 1 && app != 2);
    }

    printf("[PASS] test_pin_app\n");
    return 0;
}

int test_boost_factor() {
    camms::ArcCache cache(10 * 1024);

    cache.record_access(1, 1024, 0.9f);
    cache.record_access(2, 1024, 0.1f);

    // Access same apps again with different boost
    cache.set_boost_factor(1, 1.0f);
    cache.set_boost_factor(2, 0.0f);

    cache.record_access(1, 1024, 1.0f);

    printf("[PASS] test_boost_factor\n");
    return 0;
}

int test_clear() {
    camms::ArcCache cache(10 * 1024);
    for (int i = 0; i < 100; i++) {
        cache.record_access(i + 1, 1024);
    }
    cache.clear();
    assert(cache.current_usage_kb() == 0);
    assert(cache.stats().hits == 0);

    printf("[PASS] test_clear\n");
    return 0;
}

int main() {
    int failures = 0;
    failures += test_basic_insert_and_access();
    failures += test_eviction();
    failures += test_pin_app();
    failures += test_boost_factor();
    failures += test_clear();

    printf("\n=== ARC Cache: %d tests passed ===\n", 5 - failures);
    return failures;
}
