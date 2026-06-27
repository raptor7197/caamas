package com.main.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
        val am      = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val cores   = Runtime.getRuntime().availableProcessors()

        // HIGH: ≥6 GB total RAM → can run 8B model; LOW: capped at 1.5B
        val tier = if (totalMb >= 6_000) Tier.HIGH else Tier.LOW

        // Probe Vulkan via PackageManager feature flag
        val hasVulkan = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)

        // Context window scales with available RAM: HIGH→4096, LOW→2048
        val nCtx = if (tier == Tier.HIGH) 4096 else 2048

        val nThreads = maxOf(2, cores / 2)

        // Only use LARGE model if tier is HIGH and we have ≥3 GB free
        val modelTier = if (tier == Tier.HIGH && availMb >= 3_000) {
            ModelTier.LARGE
        } else {
            ModelTier.SMALL
        }

        return Info(
            tier               = tier,
            totalRamMb         = totalMb,
            availRamMb         = availMb,
            cpuCores           = cores,
            hasVulkan          = hasVulkan,
            recommendedCtx     = nCtx,
            recommendedThreads = nThreads,
            maxModelTier       = modelTier,
        )
    }
}
