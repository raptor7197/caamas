#include "arc_cache.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>

namespace camms {

ArcCache::ArcCache(size_t capacity_kb)
    : capacity_kb_(capacity_kb)
{
    ghost_recency_.reserve(1024);
    ghost_frequency_.reserve(1024);
}

void ArcCache::record_access(int32_t app_id, size_t working_set_kb, float boost_factor) {
    auto it = entries_.find(app_id);
    if (it != entries_.end()) {
        stats_.hits++;
        auto& entry = it->second;
        entry.last_access_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        entry.access_count++;
        entry.working_set_kb = working_set_kb;
        entry.boost_factor = std::max(entry.boost_factor, boost_factor);

        // ARC: hit in L2 (frequency list) → stay; hit in L1 → promote to L2
        bool in_freq = false;
        for (const auto& id : lru_frequency_) {
            if (id == app_id) { in_freq = true; break; }
        }

        lru_recency_.remove(app_id);
        if (in_freq) {
            lru_frequency_.remove(app_id);
            lru_frequency_.push_front(app_id);
        } else {
            // Only in recency list: promote to frequency list
            lru_frequency_.push_front(app_id);
        }
        lru_recency_.push_front(app_id);
        return;
    }

    bool ghost_hit_recency = false;
    bool ghost_hit_frequency = false;

    auto ghost_r = std::find_if(ghost_recency_.begin(), ghost_recency_.end(),
        [app_id](const GhostEntry& g) { return g.app_id == app_id; });
    auto ghost_f = std::find_if(ghost_frequency_.begin(), ghost_frequency_.end(),
        [app_id](const GhostEntry& g) { return g.app_id == app_id; });

    if (ghost_r != ghost_recency_.end()) {
        stats_.ghost_hits++;
        ghost_recency_.erase(ghost_r);
        ghost_hit_recency = true;
        adapt_parameter(true, true);
    } else if (ghost_f != ghost_frequency_.end()) {
        stats_.ghost_hits++;
        ghost_frequency_.erase(ghost_f);
        ghost_hit_frequency = true;
        adapt_parameter(true, false);
    } else {
        stats_.misses++;
    }

    ArcEntry entry;
    entry.app_id = app_id;
    entry.working_set_kb = working_set_kb;
    entry.last_access_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    entry.access_count = 1;
    entry.is_pinned = false;
    entry.boost_factor = boost_factor;

    size_t est_size = entry_size(entry);
    current_usage_kb_ += est_size;
    entries_[app_id] = std::move(entry);
    lru_recency_.push_front(app_id);

    // Ghost hit → insert into frequency list (re-activated)
    if (ghost_hit_recency || ghost_hit_frequency) {
        lru_frequency_.push_front(app_id);
    }

    trim_to_capacity();
}

bool ArcCache::should_evict(int32_t app_id, size_t needed_kb) const {
    auto it = entries_.find(app_id);
    if (it == entries_.end()) return false;
    size_t avail = capacity_kb_ > current_usage_kb_ ? capacity_kb_ - current_usage_kb_ : 0;
    return needed_kb > avail;
}

std::vector<int32_t> ArcCache::eviction_candidates(size_t needed_kb) {
    std::vector<int32_t> candidates;
    size_t freed = 0;

    for (auto it = lru_frequency_.rbegin(); it != lru_frequency_.rend() && freed < needed_kb; ++it) {
        auto eit = entries_.find(*it);
        if (eit == entries_.end() || eit->second.is_pinned) continue;
        candidates.push_back(*it);
        freed += entry_size(eit->second);
    }

    if (freed < needed_kb) {
        for (auto it = lru_recency_.rbegin(); it != lru_recency_.rend() && freed < needed_kb; ++it) {
            if (std::find(candidates.begin(), candidates.end(), *it) != candidates.end()) continue;
            auto eit = entries_.find(*it);
            if (eit == entries_.end() || eit->second.is_pinned) continue;
            candidates.push_back(*it);
            freed += entry_size(eit->second);
        }
    }

    return candidates;
}

int32_t ArcCache::evict_one() {
    size_t l1_size = 0, l1_count = 0;
    for (const auto& id : lru_recency_) {
        auto it = entries_.find(id);
        if (it != entries_.end()) {
            l1_size += entry_size(it->second);
            l1_count++;
        }
    }
    size_t l2_size = 0, l2_count = 0;
    for (const auto& id : lru_frequency_) {
        auto it = entries_.find(id);
        if (it != entries_.end()) {
            l2_size += entry_size(it->second);
            l2_count++;
        }
    }

    size_t l1_target = static_cast<size_t>(p_ * capacity_kb_);
    size_t l2_target = capacity_kb_ - l1_target;

    // Evict from the list that's over its target; if both are under, evict from L1
    if (l1_size > l1_target || l2_size <= l2_target) {
        for (auto it = lru_recency_.rbegin(); it != lru_recency_.rend(); ++it) {
            auto eit = entries_.find(*it);
            if (eit == entries_.end() || eit->second.is_pinned) continue;

            int32_t evicted = *it;
            GhostEntry ghost;
            ghost.app_id = evicted;
            ghost.evicted_at_ms = eit->second.last_access_ms;
            if (ghost_recency_.size() >= 1024) ghost_recency_.erase(ghost_recency_.begin());
            ghost_recency_.push_back(ghost);

            current_usage_kb_ -= entry_size(eit->second);
            lru_recency_.remove(evicted);
            lru_frequency_.remove(evicted);
            entries_.erase(eit);
            stats_.evictions++;
            return evicted;
        }
    }

    for (auto it = lru_frequency_.rbegin(); it != lru_frequency_.rend(); ++it) {
        auto eit = entries_.find(*it);
        if (eit == entries_.end() || eit->second.is_pinned) continue;

        int32_t evicted = *it;
        GhostEntry ghost;
        ghost.app_id = evicted;
        ghost.evicted_at_ms = eit->second.last_access_ms;
        if (ghost_frequency_.size() >= 1024) ghost_frequency_.erase(ghost_frequency_.begin());
        ghost_frequency_.push_back(ghost);

        current_usage_kb_ -= entry_size(eit->second);
        lru_recency_.remove(evicted);
        lru_frequency_.remove(evicted);
        entries_.erase(eit);
        stats_.evictions++;
        return evicted;
    }
    return -1;
}

void ArcCache::pin_app(int32_t app_id, bool pinned) {
    auto it = entries_.find(app_id);
    if (it != entries_.end()) {
        it->second.is_pinned = pinned;
    }
}

void ArcCache::set_boost_factor(int32_t app_id, float factor) {
    auto it = entries_.find(app_id);
    if (it != entries_.end()) {
        it->second.boost_factor = std::clamp(factor, 0.0f, 1.0f);
    }
}

float ArcCache::hit_rate() const {
    uint64_t total = stats_.hits + stats_.misses;
    return total > 0 ? static_cast<float>(stats_.hits) / total : 0.0f;
}

void ArcCache::clear() {
    entries_.clear();
    lru_recency_.clear();
    lru_frequency_.clear();
    ghost_recency_.clear();
    ghost_frequency_.clear();
    current_usage_kb_ = 0;
    p_ = 0.5f;
    stats_ = Stats{};
}

void ArcCache::adapt_parameter(bool ghost_hit, bool on_recency_side) {
    if (ghost_hit) {
        if (on_recency_side) {
            float delta = std::max(1.0f, static_cast<float>(ghost_frequency_.size()) /
                                std::max(1.0f, static_cast<float>(ghost_recency_.size())));
            p_ = std::min(1.0f, p_ + delta / capacity_kb_);
        } else {
            float delta = std::max(1.0f, static_cast<float>(ghost_recency_.size()) /
                                std::max(1.0f, static_cast<float>(ghost_frequency_.size())));
            p_ = std::max(0.0f, p_ - delta / capacity_kb_);
        }
    }
}

void ArcCache::trim_to_capacity() {
    while (current_usage_kb_ > capacity_kb_) {
        int32_t evicted = evict_one();
        if (evicted < 0) break;
    }
}

size_t ArcCache::entry_size(const ArcEntry& entry) const {
    return entry.working_set_kb + 4;
}

} // namespace camms
