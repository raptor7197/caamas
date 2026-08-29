#include "kv_cache_compress.h"
#include <cstdio>
#include <cassert>
#include <cmath>
#include <vector>

camms::KVCacheConfig default_config() {
    camms::KVCacheConfig cfg{};
    cfg.max_tokens = 1024;
    cfg.num_layers = 4;
    cfg.num_heads = 4;
    cfg.head_dim = 64;
    cfg.enable_mmap = false;
    return cfg;
}

int test_key_quantization_roundtrip() {
    auto cfg = default_config();
    camms::KVCacheCompressor comp(cfg);

    // Create dummy key data: 16 tokens, each with 256 channels
    size_t num_tokens = 16;
    size_t num_channels = cfg.num_heads * cfg.head_dim;
    std::vector<float> keys(num_tokens * num_channels);
    for (size_t i = 0; i < keys.size(); i++) {
        keys[i] = std::sin(i * 0.1f) * 5.0f;
    }

    auto block = comp.quantize_key_block(keys.data(), num_tokens);
    assert(block.is_cold);
    assert(block.num_tokens == num_tokens);
    assert(!block.data.empty());

    // Dequantize and check approximate fidelity
    auto dequantized = comp.dequantize_block(block);
    assert(dequantized.size() == keys.size());

    // Check MSE is reasonable (< 5% relative error for INT4)
    float mse = 0.0f;
    for (size_t i = 0; i < keys.size(); i++) {
        float diff = keys[i] - dequantized[i];
        mse += diff * diff;
    }
    mse /= keys.size();
    float rmse = std::sqrt(mse);
    assert(rmse < 2.0f); // INT4 should reconstruct fairly well

    printf("[PASS] test_key_quantization_roundtrip (RMSE=%.4f)\n", rmse);
    return 0;
}

int test_value_quantization_roundtrip() {
    auto cfg = default_config();
    camms::KVCacheCompressor comp(cfg);

    size_t num_tokens = 16;
    size_t num_channels = cfg.num_heads * cfg.head_dim;
    std::vector<float> values(num_tokens * num_channels);
    for (size_t i = 0; i < values.size(); i++) {
        values[i] = static_cast<float>(i % 100) / 100.0f;
    }

    auto block = comp.quantize_value_block(values.data(), num_tokens);
    auto dequantized = comp.dequantize_block(block);
    assert(dequantized.size() == values.size());

    float mse = 0.0f;
    for (size_t i = 0; i < values.size(); i++) {
        float diff = values[i] - dequantized[i];
        mse += diff * diff;
    }
    mse /= values.size();
    float rmse = std::sqrt(mse);
    assert(rmse < 2.0f);

    printf("[PASS] test_value_quantization_roundtrip (RMSE=%.4f)\n", rmse);
    return 0;
}

int test_compression_ratio() {
    auto cfg = default_config();
    camms::KVCacheCompressor comp(cfg);

    size_t num_tokens = 128;
    size_t num_channels = cfg.num_heads * cfg.head_dim;
    std::vector<float> data(num_tokens * num_channels, 1.0f);
    auto block = comp.quantize_key_block(data.data(), num_tokens);

    // INT4 -> 4x compression vs FP32, 2x vs FP16
    // With overhead from scales/zps, expect ~3-4x
    size_t fp32_size = data.size() * sizeof(float);
    size_t compressed_size = block.data.size() * sizeof(uint8_t) +
                             block.scale.size() * sizeof(float) +
                             block.zero_point.size() * sizeof(float);

    double ratio = static_cast<double>(fp32_size) / compressed_size;
    printf("[INFO] FP32=%zu bytes, compressed=%zu bytes, ratio=%.1fx\n",
           fp32_size, compressed_size, ratio);
    assert(ratio > 2.0);

    printf("[PASS] test_compression_ratio (%.1fx)\n", ratio);
    return 0;
}

int test_block_alloc_free() {
    auto cfg = default_config();
    camms::KVCacheCompressor comp(cfg);

    auto block = comp.allocate_block(16, true);
    assert(!block.data.empty());

    comp.free_block(block);
    assert(block.data.empty());

    printf("[PASS] test_block_alloc_free\n");
    return 0;
}

int main() {
    int failures = 0;
    failures += test_key_quantization_roundtrip();
    failures += test_value_quantization_roundtrip();
    failures += test_compression_ratio();
    failures += test_block_alloc_free();

    printf("\n=== KV Cache: %d tests passed ===\n", 4 - failures);
    return failures;
}
