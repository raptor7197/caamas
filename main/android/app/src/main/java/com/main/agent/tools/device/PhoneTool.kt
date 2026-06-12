package com.main.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import com.main.agent.tools.base.buildJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PhoneTool : Tool {
    override val name        = "make_call"
    override val description = "Open the dialer with a pre-filled number. Always requires user confirmation."
    override val schema = """{"type":"function","function":{"name":"make_call","description":"$description",
        "parameters":{"type":"object","properties":{
        "number":{"type":"string","description":"Phone number to call"},
        "confirmed":{"type":"boolean","description":"Must be true after user confirms","default":false}}
        ,"required":["number"]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val number = args["number"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'number'")
        val confirmed = args["confirmed"]?.jsonPrimitive?.content?.toBoolean() ?: false

        if (!isValidPhoneNumber(number)) {
            return ToolResult.Error("Invalid phone number: '$number'")
        }

        if (!confirmed) {
            return ToolResult.NeedsConfirmation(
                prompt   = "make_call",
                title    = "Call $number?",
                message  = "Open dialer with number $number",
                jsonData = buildJsonString { put("number", number); put("confirmed", true) },
            )
        }

        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.Success("Opened dialer for $number")
        } catch (e: Exception) {
            ToolResult.Error("Failed to open dialer: ${e.message}")
        }
    }

    private fun isValidPhoneNumber(number: String): Boolean {
        if (number.isBlank()) return false
        val digitsOnly = number.replace(Regex("[+\\-\\d\\s()#*,.]+"), "")
        return digitsOnly.isEmpty() && number.length >= 3 && number.length <= 32
    }
}
