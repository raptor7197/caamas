package com.main.agent.tools.system

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SettingsTool : Tool {
    override val name        = "device_settings"
    override val description = "Get or change device settings: WiFi (read-only), Bluetooth (read-only), volume, ringer mode."
    override val schema = """{"type":"function","function":{"name":"device_settings","description":"$description",
        "parameters":{"type":"object","properties":{
        "action":{"type":"string","enum":["get","set"],"description":"Read or write"},
        "setting":{"type":"string","enum":["wifi","bluetooth","volume","ringer"],
        "description":"Setting name"},
        "value":{"type":"string","description":"Desired value (for 'set' action)"}},
        "required":["action","setting"]}}}"""

    @Suppress("DEPRECATION")
    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val action  = args["action"]?.jsonPrimitive?.content?.trim() ?: ""
        val setting = args["setting"]?.jsonPrimitive?.content?.trim() ?: ""
        val value   = args["value"]?.jsonPrimitive?.content?.trim()

        return when (setting) {
            "wifi" -> {
                if (action == "set") {
                    ToolResult.Success("WiFi cannot be toggled programmatically on modern Android. " +
                        "Please use Quick Settings.")
                } else {
                    val wifiOn = Settings.Global.getInt(context.contentResolver,
                        Settings.Global.WIFI_ON, -1)
                    ToolResult.Success("WiFi is ${if (wifiOn == 1) "enabled" else "disabled"}")
                }
            }

            "bluetooth" -> {
                if (action == "set") {
                    ToolResult.Success("Bluetooth cannot be toggled programmatically on Android 10+. " +
                        "Please use Quick Settings.")
                } else {
                    val btOn = Settings.Global.getInt(context.contentResolver,
                        Settings.Global.BLUETOOTH_ON, -1)
                    ToolResult.Success("Bluetooth is ${if (btOn == 1) "enabled" else "disabled"}")
                }
            }

            "volume" -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (action == "set" && value != null) {
                    val vol = value.toIntOrNull()
                    if (vol == null) return ToolResult.Error("Volume must be an integer (0-100)")
                    if (vol < 0 || vol > 100) return ToolResult.Error("Volume must be 0-100")
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol * maxVol / 100,
                        AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success("Volume set to $vol%")
                } else {
                    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max     = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val pct     = current * 100 / max
                    ToolResult.Success("Media volume: $pct% ($current/$max)")
                }
            }

            "ringer" -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (action == "set" && value != null) {
                    when (value.lowercase()) {
                        "normal" -> audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        "silent" -> audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                        "vibrate" -> audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                        else -> return ToolResult.Error("Ringer value must be 'normal', 'silent', or 'vibrate'")
                    }
                    ToolResult.Success("Ringer set to $value")
                } else {
                    val modeStr = when (audio.ringerMode) {
                        AudioManager.RINGER_MODE_NORMAL -> "normal"
                        AudioManager.RINGER_MODE_SILENT -> "silent"
                        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                        else -> "unknown"
                    }
                    ToolResult.Success("Ringer mode: $modeStr")
                }
            }

            else -> ToolResult.Error("Unknown setting '$setting'. Supported: wifi, bluetooth, volume, ringer")
        }
    }
}
