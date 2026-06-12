package com.main.agent.tools.device

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContactsTool : Tool {
    override val name        = "search_contacts"
    override val description = "Search contacts by name or phone number. Returns name, phone, and email."
    override val schema = """{"type":"function","function":{"name":"search_contacts","description":"$description",
        "parameters":{"type":"object","properties":{
        "query":{"type":"string","description":"Name or number to search for"},
        "limit":{"type":"integer","description":"Max results","default":5}}
        ,"required":["query"]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult.Error("Missing 'query'")
        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

        return try {
            val cr  = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                      "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val args = arrayOf("%$query%", "%$query%")
            val cols = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )

            val cursor = cr.query(uri, cols, sel, args, null)
                ?: return ToolResult.Error("Cannot access contacts", ToolResult.ErrorCode.PERMISSION_DENIED)

            val results = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext() && results.size < limit) {
                    val name   = it.getString(0) ?: "Unknown"
                    val number = it.getString(1) ?: ""
                    results.add("$name — $number")
                }
            }

            if (results.isEmpty()) ToolResult.Success("No contacts found matching '$query'")
            else ToolResult.Success("Contacts matching '$query':\n" + results.joinToString("\n"))
        } catch (e: SecurityException) {
            ToolResult.Error("READ_CONTACTS permission not granted", ToolResult.ErrorCode.PERMISSION_DENIED)
        }
    }
}
