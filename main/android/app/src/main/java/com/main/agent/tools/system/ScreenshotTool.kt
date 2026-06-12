package com.main.agent.tools.system

import android.content.Context
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject

/** Phase 4: requires MediaProjection foreground service + user confirmation dialog. */
class ScreenshotTool : Tool {
    override val name        = "take_screenshot"
    override val description = "Capture a screenshot of the current screen. Requires user permission."
    override val schema = """{"type":"function","function":{"name":"take_screenshot","description":"$description",
        "parameters":{"type":"object","properties":{
        "save_to_folder":{"type":"boolean","description":"Save to agent folder","default":true}},"required":[]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult =
        // Phase 4: implement MediaProjection-based screenshot
        ToolResult.NeedsConfirmation(
            prompt   = "take_screenshot",
            title    = "Take screenshot?",
            message  = "Capture a screenshot of your screen",
            jsonData = args.toString(),
        )
}
