package com.main.agent.tools.device

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraTool : Tool {
    override val name        = "take_photo"
    override val description = "Open the camera to take a photo and save it to the agent folder."
    override val schema = """{"type":"function","function":{"name":"take_photo","description":"$description",
        "parameters":{"type":"object","properties":{
        "filename":{"type":"string","description":"Optional filename (alphanumeric, dash, underscore only)"}},
        "required":[]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val rawName  = args["filename"]?.jsonPrimitive?.content ?: "photo_$ts.jpg"
        val filename = sanitizeFilename(rawName)
        val confirmed = args["confirmed"]?.jsonPrimitive?.content?.toBoolean() ?: false

        return try {
            val cacheDir = File(context.cacheDir, "camera").also { it.mkdirs() }
            val photoFile = File(cacheDir, filename)
            if (!photoFile.exists()) photoFile.createNewFile()

            val photoUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                photoFile
            )

            if (confirmed) {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                context.startActivity(intent)
                ToolResult.Success("Camera opened for: $filename. Ask the user what they see in the photo.")
            } else {
                ToolResult.NeedsConfirmation(
                    prompt   = "camera_launch",
                    title    = "Take a photo?",
                    message  = "Open camera to capture photo: $filename",
                    jsonData = """{"confirmed":true,"filename":"$filename"}""",
                )
            }
        } catch (e: Exception) {
            ToolResult.Error("Failed to prepare camera: ${e.message}")
        }
    }

    private fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        if (sanitized.length > 120) return sanitized.take(120)
        if (sanitized.isBlank()) return "photo_${System.currentTimeMillis()}.jpg"
        return sanitized
    }
}
