package com.camms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File

class CammsService : Service() {

    companion object {
        const val TAG = "CAMMS"
        const val NOTIFICATION_ID = 42
        const val CHANNEL_ID = "camms_memory_service"
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var controlJob: Job? = null
    private var thermalMonitorJob: Job? = null

    private lateinit var powerManager: PowerManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var notificationManager: NotificationManager

    private val appHistory = mutableListOf<Int>()
    private var lastPredictions = intArrayOf(-1, 0, -1, -1)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CAMMS daemon service starting...")

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        initNativeDaemon()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "CAMMS service running in foreground")

        if (controlJob == null) {
            startControlLoop()
        }
        if (thermalMonitorJob == null) {
            startThermalMonitor()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "CAMMS service stopping...")
        controlJob?.cancel()
        thermalMonitorJob?.cancel()
        scope.cancel()
        NativeBridge.cammsShutdown()
        super.onDestroy()
    }

    private fun initNativeDaemon() {
        val configPath = File(filesDir, "camms_config.json").absolutePath
        val success = NativeBridge.cammsInit(configPath)
        if (success) {
            NativeBridge.cammsStart()
            Log.i(TAG, "Native daemon initialized")
        } else {
            Log.e(TAG, "Failed to initialize native daemon")
        }
    }

    private fun startControlLoop() {
        controlJob = scope.launch {
            while (isActive) {
                try {
                    runControlCycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Control cycle error", e)
                }
                delay(3000) // 3-second cycle matching daemon
            }
        }
        Log.i(TAG, "Control loop started (3s interval)")
    }

    private suspend fun runControlCycle() {
        // 1. Get latest app usage
        val currentApp = getForegroundApp()
        if (currentApp != null && (appHistory.isEmpty() || appHistory.last() != currentApp)) {
            appHistory.add(currentApp)
            NativeBridge.recordAppLaunch(currentApp)

            if (appHistory.size > 50) {
                appHistory.removeAt(0)
            }

            // 2. Predict next app
            if (appHistory.size >= 3) {
                val historyArray = appHistory.takeLast(10).toIntArray()
                lastPredictions = NativeBridge.predictNextApp(historyArray)
                val predictedId = lastPredictions[0]
                val confidence = lastPredictions[1] / 1000f

                Log.d(TAG, "Prediction: app=$predictedId confidence=$confidence")

                // 3. Confidence-gated preloading
                val thermalLevel = NativeBridge.getThermalLevel()
                if (predictedId > 0 && confidence >= 0.6f && thermalLevel <= 1) {
                    val preloaded = NativeBridge.preloadApp(predictedId)
                    if (preloaded) {
                        Log.d(TAG, "Preloaded app $predictedId (confidence=$confidence)")
                    }
                } else if (predictedId > 0 && confidence >= 0.5f && thermalLevel <= 1) {
                    // Tier 2: relaxed gate
                    NativeBridge.preloadApp(predictedId)
                }
            }
        }

        // 4. Memory pressure response
        val pressure = getMemoryPressure()
        if (pressure > 0.7f) {
            Log.w(TAG, "High memory pressure ($pressure), triggering compaction")
            val freed = NativeBridge.compactZram(64 * 1024 * 1024) // 64MB
            if (freed > 0) {
                Log.i(TAG, "zRAM compaction freed $freed bytes")
            }
        }

        // 5. Periodic ARC stats logging
        if (System.currentTimeMillis() % 15000 < 3000) { // every ~15s
            val totalPss = NativeBridge.getTotalPssKb()
            val hitRate = NativeBridge.getArcHitRate()
            val usage = NativeBridge.getArcUsageKb()
            Log.i(TAG, "STATS: PSS=${totalPss / 1024}MB ARC_usage=${usage / 1024}MB hit_rate=$hitRate")
        }
    }

    private fun startThermalMonitor() {
        thermalMonitorJob = scope.launch {
            while (isActive) {
                try {
                    val headroom = getThermalHeadroomAndroid()
                    NativeBridge.setThermalHeadroom(headroom)

                    if (headroom > 0.85f) {
                        Log.w(TAG, "Thermal CRITICAL (headroom=$headroom), throttling")
                    }
                } catch (_: Exception) {}

                delay(2000)
            }
        }
    }

    private fun getThermalHeadroomAndroid(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                powerManager.getThermalHeadroom(60)
            } catch (_: Exception) {
                0.0f
            }
        } else 0.0f
    }

    private fun getForegroundApp(): Int? {
        return try {
            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, 0, System.currentTimeMillis()
            )
            var topApp: String? = null
            var topTime = 0L
            for (stats in usageStats) {
                val lastTime = stats.lastTimeUsed
                if (lastTime > topTime) {
                    topTime = lastTime
                    topApp = stats.packageName
                }
            }
            topApp?.hashCode()
        } catch (_: Exception) {
            null
        }
    }

    private fun getMemoryPressure(): Float {
        return try {
            val memInfo = java.io.File("/proc/meminfo").readLines()
            val memAvail = memInfo.find { it.startsWith("MemAvailable") }
                ?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: return 0.0f
            val memTotal = memInfo.find { it.startsWith("MemTotal") }
                ?.replace(Regex("[^0-9]"), "")?.toLongOrNull() ?: return 0.0f
            if (memTotal > 0) 1.0f - (memAvail.toFloat() / memTotal.toFloat()) else 0.0f
        } catch (_: Exception) {
            0.0f
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CAMMS Memory Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Context-Aware Adaptive Memory Management"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CAMMS Active")
            .setContentText("Adaptive memory management running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
