package com.main.agent.tools.knowledge

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import com.main.agent.tools.base.buildJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MapsTool : Tool {
    override val name        = "open_maps"
    override val description = "Open navigation directions or show a location in Google Maps (falls back to OSM)."
    override val schema = """{"type":"function","function":{"name":"open_maps","description":"$description",
        "parameters":{"type":"object","properties":{
        "destination":{"type":"string","description":"Address or place name"},
        "origin":{"type":"string","description":"Optional starting point (default: current location)"}},
        "required":["destination"]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val dest = args["destination"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'destination'")
        val origin = args["origin"]?.jsonPrimitive?.content?.trim()
        val confirmed = args["confirmed"]?.jsonPrimitive?.content?.toBoolean() ?: false

        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                prompt   = "open_maps",
                title    = "Open Maps?",
                message  = "Navigate to $dest${if (origin != null) " from $origin" else ""}",
                jsonData = buildJsonString {
                    put("destination", dest)
                    if (origin != null) put("origin", origin)
                    put("confirmed", true)
                },
            )
        }

        return try {
            val uri = if (origin != null) {
                "https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(origin)}&destination=${Uri.encode(dest)}"
            } else {
                "https://www.google.com/maps/search/?api=1&query=${Uri.encode(dest)}"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                ToolResult.Success("Opened Maps with destination: $dest")
            } catch (e: Exception) {
                val osmUri = if (origin != null) {
                    "https://www.openstreetmap.org/directions?from=${Uri.encode(origin)}&to=${Uri.encode(dest)}"
                } else {
                    "https://www.openstreetmap.org/search?q=${Uri.encode(dest)}"
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(osmUri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ToolResult.Success("Opened OSM with destination: $dest")
            }
        } catch (e: Exception) {
            ToolResult.Error("Failed to open maps: ${e.message}")
        }
    }
}
