package com.camms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

class FlowerClientService : Service() {

    companion object {
        const val TAG = "CAMMS_FL"
        const val SERVER_ADDRESS = "10.0.2.2:8080" // default emulator host
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flJob: Job? = null

    private lateinit var powerManager: PowerManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var batteryManager: BatteryManager

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        Log.i(TAG, "Flower client initialized")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        flJob = scope.launch {
            waitForIdleCharging()
            startFederatedLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        flJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun waitForIdleCharging() {
        while (isActive) {
            val isCharging = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                == BatteryManager.BATTERY_STATUS_CHARGING
            val isOnWifi = connectivityManager.getNetworkCapabilities(
                connectivityManager.activeNetwork
            )?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isIdle = !powerManager.isInteractive

            if (isCharging && isOnWifi && isIdle) {
                Log.i(TAG, "Idle + charging + WiFi → FL round ready")
                return
            }
            delay(60000) // check every minute
        }
    }

    private suspend fun startFederatedLoop() {
        Log.i(TAG, "Starting federated learning loop")
        val flowerClient = CammsFlowerClient(this)

        while (isActive) {
            try {
                Log.d(TAG, "Connecting to FL server at $SERVER_ADDRESS")
                flowerClient.run(SERVER_ADDRESS)
                Log.i(TAG, "FL round completed")

                // Save updated weights from server
                val weights = NativeBridge.getModelWeights()
                if (weights.isNotEmpty()) {
                    // Persist weights for next inference session
                    Log.d(TAG, "Received ${weights.size} weight values from server")
                }
            } catch (e: Exception) {
                Log.e(TAG, "FL round failed", e)
            }

            // Wait before next round (4-24 hours in production)
            delay(4 * 3600 * 1000L)
        }
    }
}

class CammsFlowerClient(private val context: Context) {
    fun run(serverAddress: String) {
        val appHistory = collectLocalTrainingData()
        if (appHistory.size < 10) return

        // TODO: Implement Flower NumPyClient
        // This requires the Flower Android SDK:
        //   FlowerClient(grpc_address, grpc_resolver)
        //     .run() -> fits model, returns weights

        Log.i("CAMMS_FL", "FL client would send ${appHistory.size} samples")
    }

    private fun collectLocalTrainingData(): List<IntArray> {
        val samples = mutableListOf<IntArray>()
        val rawHistory = mutableListOf<Int>()

        // Read app usage logs from local storage or NativeBridge
        // For now, return empty list - data collection is wired in CammsService
        return samples
    }
}
