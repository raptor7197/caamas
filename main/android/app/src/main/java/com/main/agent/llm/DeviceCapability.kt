package com.main.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Detects device hardware capability at runtime and assigns a processing tier.
 *
 * TIER_HIGH  → 8 GB RAM, can run 7B models, may have GPU acceleration
 * TIER_LOW   → < 5 GB usable RAM, capped at 2B models, CPU-only
 */
object DeviceCapability {

    enum class Tier { HIGH, LOW }

    data class Info(
        val tier: Tier,
        val totalRamMb: Long,
        val availRamMb: Long,
        val cpuCores: Int,
        val hasVulkan: Boolean,
        val recommendedCtx: Int,
        val recommendedThreads: Int,
        val maxModelTier: ModelTier,
    )

    enum class ModelTier(val label: String) {
        SMALL("Qwen 2.5 1.5B"),
        LARGE("Llama 3.1 8B"),
    }

    fun detect(context: Context): Info {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val cores   = Runtime.getRuntime().availableProcessors()

        // 8 GB device needs ≥ 6 GB total; we keep 1 GB headroom for OS + app
        val tier = if (totalMb >= 6_000) Tier.HIGH else Tier.LOW

        // Infer Vulkan: Mali-G610 (Dimensity 7200) supports it; Mali-G52 does not.
        // We use a conservative heuristic: TIER_HIGH on Android 12 + Adreno/Mali-G6xx
        val hasVulkan = false // Mali-G610 Vulkan backend causes ggml crashes; disable for now

        // Context window: HIGH=4096, LOW=2048 to avoid OOM
        val nCtx = 2048 // reduced to avoid OOM

        // Use half the cores for inference threads to leave room for UI thread
        val nThreads = maxOf(2, cores / 2)

        val modelTier = ModelTier.SMALL // force small model for now; large 4.9 GB causes OOM

        return Info(
            tier              = tier,
            totalRamMb        = totalMb,
            availRamMb        = availMb,
            cpuCores          = cores,
            hasVulkan         = hasVulkan,
            recommendedCtx    = nCtx,
            recommendedThreads = nThreads,
            maxModelTier      = modelTier,
        )
    }
}
