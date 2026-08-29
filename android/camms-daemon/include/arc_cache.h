#ifndef CAMMS_ARC_CACHE_H
#define CAMMS_ARC_CACHE_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <list>
#include <vector>

namespace camms {

struct ArcEntry {
    int32_t app_id;
    size_t working_set_kb;
    uint64_t last_access_ms;
    uint32_t access_count;
    bool is_pinned;
    float boost_factor;  // ML advisor weight [0, 1]
};

class ArcCache {
public:
    explicit ArcCache(size_t cache_capacity_kb);

    void record_access(int32_t app_id, size_t working_set_kb, float boost_factor = 0.0f);
    bool should_evict(int32_t app_id, size_t needed_kb) const;
    std::vector<int32_t> eviction_candidates(size_t needed_kb);
    int32_t evict_one();
    void pin_app(int32_t app_id, bool pinned);
    void set_boost_factor(int32_t app_id, float factor);

    size_t current_usage_kb() const { return current_usage_kb_; }
    size_t capacity_kb() const { return capacity_kb_; }
    float hit_rate() const;
    void clear();

    struct Stats {
        uint64_t hits{0};
        uint64_t misses{0};
        uint64_t evictions{0};
        uint64_t ghost_hits{0};
    };
    Stats stats() const { return stats_; }

private:
    struct GhostEntry {
        int32_t app_id;
        uint64_t evicted_at_ms;
    };

    using AppMap = std::unordered_map<int32_t, ArcEntry>;
    using LRUList = std::list<int32_t>;

    AppMap entries_;
    LRUList lru_recency_;   // L1: recency-ordered
    LRUList lru_frequency_; // L2: frequency-ordered
    std::vector<GhostEntry> ghost_recency_; // B1: recency ghosts
    std::vector<GhostEntry> ghost_frequency_; // B2: frequency ghosts

    size_t capacity_kb_;
    size_t current_usage_kb_{0};
    float p_{0.5f}; // adaptive ARC parameter [0, 1]

    Stats stats_;

    void adapt_parameter(bool ghost_hit, bool on_recency_side);
    void trim_to_capacity();
    size_t entry_size(const ArcEntry& entry) const;
};

} // namespace camms

#endif // CAMMS_ARC_CACHE_H
