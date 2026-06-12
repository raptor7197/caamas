package com.main.agent.tools.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class AlarmCalendarTool : Tool {
    override val name        = "calendar"
    override val description = "Read upcoming calendar events, create an event, or set an alarm."
    override val schema = """{"type":"function","function":{"name":"calendar","description":"$description",
        "parameters":{"type":"object","properties":{
        "action":{"type":"string","enum":["read_events","create_event","set_alarm"]},
        "days_ahead":{"type":"integer","description":"For read_events: how many days ahead (default 7)","default":7},
        "title":{"type":"string","description":"Event or alarm title"},
        "start_time":{"type":"string","description":"ISO 8601 datetime e.g. '2025-01-15T14:00:00'"},
        "end_time":{"type":"string","description":"ISO 8601 datetime for event end"},
        "hour":{"type":"integer","description":"Alarm hour (0-23)"},
        "minute":{"type":"integer","description":"Alarm minute (0-59)"}}
        ,"required":["action"]}}}"""

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        return when (args["action"]?.jsonPrimitive?.content) {
            "read_events"  -> readEvents(context, args["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7)
            "create_event" -> createEvent(context, args)
            "set_alarm"    -> setAlarm(context, args)
            else -> ToolResult.Error("Unknown action. Use read_events, create_event, or set_alarm.")
        }
    }

    private fun readEvents(context: Context, daysAhead: Int): ToolResult {
        return try {
            val now  = System.currentTimeMillis()
            val end  = now + daysAhead.toLong() * 86400000L
            val uri  = CalendarContract.Events.CONTENT_URI
            val cols = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
            )
            val sel  = "${CalendarContract.Events.DTSTART} BETWEEN ? AND ?"
            val sArgs = arrayOf(now.toString(), end.toString())

            val cursor = context.contentResolver.query(uri, cols, sel, sArgs, "${CalendarContract.Events.DTSTART} ASC")
                ?: return ToolResult.Error("Cannot access calendar", ToolResult.ErrorCode.PERMISSION_DENIED)

            val df   = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
            val sb   = StringBuilder("Upcoming events (next $daysAhead days):\n\n")
            var n    = 0
            cursor.use {
                while (it.moveToNext()) {
                    val title    = it.getString(0) ?: "Untitled"
                    val start    = df.format(Date(it.getLong(1)))
                    val location = it.getString(3)?.takeIf { l -> l.isNotBlank() }
                    sb.append("• $title — $start")
                    if (location != null) sb.append(" @ $location")
                    sb.append("\n")
                    n++
                }
            }
            if (n == 0) ToolResult.Success("No events in the next $daysAhead days.")
            else ToolResult.Success(sb.toString().trim())
        } catch (e: SecurityException) {
            ToolResult.Error("READ_CALENDAR permission not granted", ToolResult.ErrorCode.PERMISSION_DENIED)
        }
    }

    private fun createEvent(context: Context, args: JsonObject): ToolResult {
        val title = args["title"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'title'")
        val startStr = args["start_time"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing 'start_time'")
        val endStr = args["end_time"]?.jsonPrimitive?.contentOrNull ?: startStr

        return try {
            val startMs = iso.parse(startStr)?.time ?: return ToolResult.Error("Invalid start_time format")
            val endMs   = iso.parse(endStr)?.time   ?: startMs + 3600000L

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data   = CalendarContract.Events.CONTENT_URI
                flags  = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME,   endMs)
                putExtra(CalendarContract.Events.TITLE,           title)
            }
            context.startActivity(intent)
            ToolResult.Success("Opened calendar to create: $title on $startStr")
        } catch (e: Exception) {
            ToolResult.Error("Failed to create event: ${e.message}")
        }
    }

    private fun setAlarm(context: Context, args: JsonObject): ToolResult {
        val hour   = args["hour"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error("Missing 'hour'")
        val minute = args["minute"]?.jsonPrimitive?.intOrNull ?: 0
        val title  = args["title"]?.jsonPrimitive?.contentOrNull ?: "Agent Alarm"
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, title)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            context.startActivity(intent)
            val amPm = if (hour < 12) "AM" else "PM"
            val h    = if (hour % 12 == 0) 12 else hour % 12
            ToolResult.Success("Alarm set for $h:%02d $amPm — $title".format(minute))
        } catch (e: Exception) {
            ToolResult.Error("Failed to set alarm: ${e.message}")
        }
    }
}
