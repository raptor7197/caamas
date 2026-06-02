package com.camms

object NativeBridge {
    init {
        System.loadLibrary("camms_core")
    }

    // Lifecycle
    external fun cammsInit(configPath: String): Boolean
    external fun cammsStart(): Boolean
    external fun cammsStop()
    external fun cammsShutdown()

    // Memory state
    external fun getTotalPssKb(): Long
    external fun getTotalRssKb(): Long
    external fun getTotalSwapKb(): Long
    external fun getActiveProcessCount(): Int
    external fun getArcHitRate(): Float
    external fun getArcUsageKb(): Long

    // Predictions
    external fun predictNextApp(appHistory: IntArray): IntArray  // returns [appId, confidence*1000, top2, top3]
    external fun getPredictionConfidence(): Float
    external fun recordAppLaunch(appId: Int)

    // Actions
    external fun preloadApp(appId: Int): Boolean
    external fun compactZram(targetBytes: Long): Long
    external fun evictApp(appId: Int): Boolean

    // Thermal
    external fun getThermalLevel(): Int     // 0=COOL, 1=WARM, 2=HOT, 3=CRITICAL
    external fun getThermalHeadroom(): Float
    external fun setThermalHeadroom(headroom: Float)

    // FL - get/apply model weights
    external fun getModelWeights(): FloatArray
    external fun setModelWeights(weights: FloatArray): Boolean

    // Config
    external fun setConfidenceThreshold(high: Float, low: Float)
    external fun setCacheCapacityKb(capacityKb: Long)
}
