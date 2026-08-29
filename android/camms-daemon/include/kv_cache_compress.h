#ifndef CAMMS_KV_CACHE_COMPRESS_H
#define CAMMS_KV_CACHE_COMPRESS_H

#include <cstddef>
#include <cstdint>
#include <vector>
#include <unordered_map>
#include <memory>

namespace camms {

// 16KB block size for paged KV cache allocation
constexpr size_t KV_BLOCK_SIZE = 16 * 1024;

// INT4 quantization: pack 2 values per byte
struct QuantizedBlock {
    std::vector<uint8_t> data;       // quantized values (INT4 packed)
    std::vector<float> scale;        // per-channel scale factors
    std::vector<float> zero_point;   // per-channel zero points
    uint32_t num_tokens;
    uint32_t num_channels;
    bool is_cold;                    // true if this block is quantized
    uint64_t last_access_seq;        // for hot/cold demotion
};

struct KVCacheConfig {
    size_t max_tokens{4096};
    size_t hot_token_ratio{8};       // 1/8 of cache stays FP16
    size_t block_tokens{16};         // tokens per block
    size_t num_layers{32};
    size_t num_heads{32};
    size_t head_dim{128};
    bool enable_mmap{false};
    std::string mmap_path;
};

class KVCacheCompressor {
public:
    explicit KVCacheCompressor(const KVCacheConfig& config);

    // Quantize a key block: per-channel INT4 for keys
    QuantizedBlock quantize_key_block(const float* key_data, size_t num_tokens);

    // Quantize a value block: per-token INT4 for values
    QuantizedBlock quantize_value_block(const float* value_data, size_t num_tokens);

    // Dequantize a block back to float
    std::vector<float> dequantize_block(const QuantizedBlock& block) const;

    // Allocate a new block (from mmap pool or heap)
    QuantizedBlock allocate_block(size_t num_tokens, bool cold);

    // Free a block (return to pool)
    void free_block(QuantizedBlock& block);

    // Demote hot tokens to cold (quantize FP16 -> INT4)
    void demote_to_cold(uint32_t layer_id, uint32_t start_token, uint32_t count);

    // Promote cold tokens to hot (dequantize INT4 -> FP16)
    std::vector<float> promote_to_hot(uint32_t layer_id, uint32_t token_id);

    // Stats
    size_t total_memory_bytes() const;
    size_t compressed_memory_bytes() const;
    double compression_ratio() const;
    size_t num_blocks_allocated() const { return blocks_.size(); }

    struct Stats {
        size_t hot_bytes{0};
        size_t cold_bytes{0};
        size_t total_bytes_without_compression{0};
        size_t mmap_bytes{0};
        uint64_t quantize_count{0};
        uint64_t dequantize_count{0};
        uint32_t num_hot_blocks{0};
        uint32_t num_cold_blocks{0};
    };
    Stats stats() const { return stats_; }

private:
    KVCacheConfig config_;
    mutable Stats stats_;
    std::unordered_map<uint32_t, QuantizedBlock> blocks_;
    uint32_t next_block_id_{0};
    std::vector<uint8_t> mmap_pool_;
    size_t mmap_offset_{0};

    void pack_int4(const float* input, uint8_t* output, float scale, float zp, size_t n) const;
    void unpack_int4(const uint8_t* input, float* output, float scale, float zp, size_t n) const;
    float compute_scale(const float* data, size_t n) const;
    float compute_zero_point(const float* data, size_t n, float scale) const;
    void ensure_mmap_pool(size_t needed);
};

} // namespace camms

#endif // CAMMS_KV_CACHE_COMPRESS_H
