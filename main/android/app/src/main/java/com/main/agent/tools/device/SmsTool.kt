package com.main.agent.tools.device

import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.main.agent.tools.base.buildJsonString

class SmsTool : Tool {
    override val name        = "send_sms"
    override val description = "Send an SMS or read the SMS inbox (sender only, no body)."
    override val schema = """{"type":"function","function":{"name":"send_sms","description":"$description",
        "parameters":{"type":"object","properties":{
        "action":{"type":"string","description":"'send' or 'read_inbox'"},
        "to":{"type":"string","description":"Phone number (required for 'send')"},
        "message":{"type":"string","description":"SMS body (required for 'send')"},
        "confirmed":{"type":"boolean","description":"Set true when user confirms"},
        "limit":{"type":"integer","description":"Max messages to read (for 'read_inbox', default 10)"}},
        "required":["action"]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content?.trim() ?: ""
        return when (action) {
            "send" -> {
                val to  = args["to"]?.jsonPrimitive?.content?.trim()
                    ?: return ToolResult.Error("Missing 'to' for send")
                val msg = args["message"]?.jsonPrimitive?.content?.trim()
                    ?: return ToolResult.Error("Missing 'message' for send")
                val confirmed = args["confirmed"]?.jsonPrimitive?.content?.toBoolean() ?: false

                if (!confirmed) {
                    ToolResult.NeedsConfirmation(
                        prompt   = "sms_send",
                        title    = "Send SMS?",
                        message  = "Send '$msg' to $to",
                        jsonData = buildJsonString { put("to", to); put("message", msg); put("confirmed", true) },
                    )
                } else {
                    try {
                        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            context.getSystemService(SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION") SmsManager.getDefault()
                        }
                        smsManager.sendTextMessage(to, null, msg, null, null)
                        ToolResult.Success("SMS sent to $to")
                    } catch (e: Exception) {
                        ToolResult.Error("Failed to send SMS: ${e.message}")
                    }
                }
            }

            "read_inbox" -> {
                val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                readInbox(context, limit)
            }

            else -> ToolResult.Error("Unknown action '$action'. Use 'send' or 'read_inbox'.")
        }
    }

    private fun readInbox(context: Context, limit: Int): ToolResult {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC"
            ) ?: return ToolResult.Error("Cannot access SMS inbox",
                ToolResult.ErrorCode.PERMISSION_DENIED)

            val messages = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext() && messages.size < limit) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val date    = it.getString(it.getColumnIndexOrThrow("date"))
                    messages.add("From: $address at $date")
                }
            }

            if (messages.isEmpty()) ToolResult.Success("No SMS messages found")
            else ToolResult.Success("Recent SMS (sender only):\n" + messages.joinToString("\n"))
        } catch (e: SecurityException) {
            ToolResult.Error("SMS permission not granted", ToolResult.ErrorCode.PERMISSION_DENIED)
        }
    }
}
