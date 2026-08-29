#include "kv_cache_compress.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

namespace camms {

KVCacheCompressor::KVCacheCompressor(const KVCacheConfig& config)
    : config_(config)
{
    if (config_.enable_mmap) {
        ensure_mmap_pool(config_.max_tokens * config_.num_layers *
                         config_.num_heads * config_.head_dim * 2);
    }
}

QuantizedBlock KVCacheCompressor::quantize_key_block(const float* key_data, size_t num_tokens) {
    QuantizedBlock block = allocate_block(num_tokens, true);
    block.is_cold = true;
    block.num_tokens = num_tokens;
    block.num_channels = config_.num_heads * config_.head_dim;

    size_t num_channels = block.num_channels;
    size_t total_values = num_tokens * num_channels;

    block.scale.resize(num_channels);
    block.zero_point.resize(num_channels);
    block.data.resize((total_values + 1) / 2); // INT4 packed

    // Per-channel quantization for keys (KIVI-style)
    for (size_t c = 0; c < num_channels; c++) {
        std::vector<float> channel_data(num_tokens);
        for (size_t t = 0; t < num_tokens; t++) {
            channel_data[t] = key_data[t * num_channels + c];
        }

        block.scale[c] = compute_scale(channel_data.data(), num_tokens);
        block.zero_point[c] = compute_zero_point(channel_data.data(), num_tokens, block.scale[c]);

        // Pack INT4
        size_t offset = c * num_tokens / 2;
        pack_int4(channel_data.data(), block.data.data() + offset,
                  block.scale[c], block.zero_point[c], num_tokens);
    }

    stats_.quantize_count++;
    stats_.num_cold_blocks++;
    stats_.cold_bytes += block.data.size() * sizeof(uint8_t) +
                         block.scale.size() * sizeof(float) * 2;
    return block;
}

QuantizedBlock KVCacheCompressor::quantize_value_block(const float* value_data, size_t num_tokens) {
    QuantizedBlock block = allocate_block(num_tokens, true);
    block.is_cold = true;
    block.num_tokens = num_tokens;
    block.num_channels = config_.num_heads * config_.head_dim;

    size_t num_channels = block.num_channels;
    size_t total_values = num_tokens * num_channels;

    block.scale.resize(num_tokens);
    block.zero_point.resize(num_tokens);
    block.data.resize((total_values + 1) / 2);

    // Per-token quantization for values (KIVI-style)
    for (size_t t = 0; t < num_tokens; t++) {
        block.scale[t] = compute_scale(value_data + t * num_channels, num_channels);
        block.zero_point[t] = compute_zero_point(value_data + t * num_channels, num_channels, block.scale[t]);
        pack_int4(value_data + t * num_channels, block.data.data() + t * num_channels / 2,
                  block.scale[t], block.zero_point[t], num_channels);
    }

    stats_.quantize_count++;
    stats_.num_cold_blocks++;
    stats_.cold_bytes += block.data.size() * sizeof(uint8_t) +
                         block.scale.size() * sizeof(float) * 2;
    return block;
}

std::vector<float> KVCacheCompressor::dequantize_block(const QuantizedBlock& block) const {
    size_t total = block.num_tokens * block.num_channels;
    std::vector<float> result(total);

    if (!block.is_cold) {
        // Block is already FP16 - just copy (not implemented for brevity)
        return result;
    }

    if (block.scale.size() == block.num_channels) {
        // Per-channel dequant (keys)
        for (size_t c = 0; c < block.num_channels; c++) {
            std::vector<float> channel_data(block.num_tokens);
            unpack_int4(block.data.data() + c * block.num_tokens / 2,
                        channel_data.data(), block.scale[c], block.zero_point[c],
                        block.num_tokens);

            for (size_t t = 0; t < block.num_tokens; t++) {
                result[t * block.num_channels + c] = channel_data[t];
            }
        }
    } else {
        // Per-token dequant (values)
        for (size_t t = 0; t < block.num_tokens; t++) {
            unpack_int4(block.data.data() + t * block.num_channels / 2,
                        result.data() + t * block.num_channels,
                        block.scale[t], block.zero_point[t], block.num_channels);
        }
    }

    stats_.dequantize_count++;
    return result;
}

QuantizedBlock KVCacheCompressor::allocate_block(size_t num_tokens, bool cold) {
    QuantizedBlock block{};
    block.num_tokens = num_tokens;
    block.num_channels = config_.num_heads * config_.head_dim;
    block.is_cold = cold;

    size_t element_bytes = cold ? (num_tokens * block.num_channels + 1) / 2 : // INT4 packed
                                   num_tokens * block.num_channels * 4;      // FP32
    block.data.resize(element_bytes);

    if (config_.enable_mmap) {
        ensure_mmap_pool(element_bytes);
    }

    return block;
}

void KVCacheCompressor::free_block(QuantizedBlock& block) {
    block.data.clear();
    block.scale.clear();
    block.zero_point.clear();
    block.data.shrink_to_fit();
    block.scale.shrink_to_fit();
    block.zero_point.shrink_to_fit();
}

void KVCacheCompressor::demote_to_cold(uint32_t layer_id, uint32_t start_token, uint32_t count) {
    (void)layer_id;
    (void)start_token;
    (void)count;
    // In production: read FP16 tokens, quantize, store as cold block
    stats_.num_hot_blocks--;
    stats_.num_cold_blocks++;
}

std::vector<float> KVCacheCompressor::promote_to_hot(uint32_t layer_id, uint32_t token_id) {
    (void)layer_id;
    (void)token_id;
    // In production: find cold block, dequantize, return float buffer
    return {};
}

size_t KVCacheCompressor::total_memory_bytes() const {
    size_t fp16_bytes = config_.max_tokens * config_.num_layers *
                        config_.num_heads * config_.head_dim * 2;
    return fp16_bytes;
}

size_t KVCacheCompressor::compressed_memory_bytes() const {
    return stats_.hot_bytes + stats_.cold_bytes + stats_.mmap_bytes;
}

double KVCacheCompressor::compression_ratio() const {
    size_t uncompressed = total_memory_bytes();
    size_t compressed = compressed_memory_bytes();
    if (compressed == 0) return 1.0;
    return static_cast<double>(uncompressed) / compressed;
}

void KVCacheCompressor::pack_int4(const float* input, uint8_t* output,
                                  float scale, float zp, size_t n) const {
    for (size_t i = 0; i < n; i += 2) {
        int8_t v0 = static_cast<int8_t>(std::clamp(std::round((input[i] / scale) + zp), 0.0f, 15.0f));
        int8_t v1 = (i + 1 < n) ?
            static_cast<int8_t>(std::clamp(std::round((input[i + 1] / scale) + zp), 0.0f, 15.0f)) : 0;
        output[i / 2] = (v0 & 0x0F) | ((v1 & 0x0F) << 4);
    }
}

void KVCacheCompressor::unpack_int4(const uint8_t* input, float* output,
                                    float scale, float zp, size_t n) const {
    for (size_t i = 0; i < n; i += 2) {
        uint8_t packed = input[i / 2];
        output[i] = ((packed & 0x0F) - zp) * scale;
        if (i + 1 < n) {
            output[i + 1] = (((packed >> 4) & 0x0F) - zp) * scale;
        }
    }
}

float KVCacheCompressor::compute_scale(const float* data, size_t n) const {
    float min_val = *std::min_element(data, data + n);
    float max_val = *std::max_element(data, data + n);
    float range = max_val - min_val;
    return (range > 0.0f) ? range / 15.0f : 1.0f;
}

float KVCacheCompressor::compute_zero_point(const float* data, size_t n, float scale) const {
    float min_val = *std::min_element(data, data + n);
    return std::round(-min_val / scale);
}

void KVCacheCompressor::ensure_mmap_pool(size_t needed) {
    if (mmap_pool_.size() >= needed) return;

    size_t page_aligned = ((needed + 4095) / 4096) * 4096;
    mmap_pool_.resize(page_aligned);
    stats_.mmap_bytes = page_aligned;
}

} // namespace camms
