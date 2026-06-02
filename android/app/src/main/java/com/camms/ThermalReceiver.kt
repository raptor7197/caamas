package com.camms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

class ThermalReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "CAMMS_THERMAL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        if (action == PowerManager.ACTION_THERMAL_STATUS_CHANGED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val status = intent.getIntExtra(
                    PowerManager.EXTRA_THERMAL_STATUS,
                    PowerManager.THERMAL_STATUS_NONE
                )

                val headroom = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .getThermalHeadroom(60)

                NativeBridge.setThermalHeadroom(headroom)

                val level = when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }

                Log.i(TAG, "Thermal status changed: $level (headroom=$headroom)")
            }
        }
    }
}
