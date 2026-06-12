package com.main.agent.tools.knowledge

import android.content.Context
import android.location.Location
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

class WeatherTool : Tool {
    override val name        = "get_weather"
    override val description = "Get current weather and today's forecast for a location. Uses OpenMeteo (free, no API key)."
    override val schema = """{"type":"function","function":{"name":"get_weather","description":"$description",
        "parameters":{"type":"object","properties":{
        "location":{"type":"string","description":"City name or 'lat,lon' coordinates"},
        "units":{"type":"string","enum":["celsius","fahrenheit"],"default":"celsius"}}
        ,"required":["location"]}}}"""

    private val client = OkHttpClient.Builder().build()

    override suspend fun execute(context: Context, args: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            val location = args["location"]?.jsonPrimitive?.content
                ?: return@withContext ToolResult.Error("Missing 'location'")
            val units = args["units"]?.jsonPrimitive?.contentOrNull ?: "celsius"
            val tempUnit = if (units == "fahrenheit") "fahrenheit" else "celsius"

            // Resolve location to lat/lon via OpenMeteo geocoder
            val (lat, lon) = resolveLatLon(location)
                ?: return@withContext ToolResult.Error(
                    "Could not geocode '$location'. Try 'City, Country' format.",
                    ToolResult.ErrorCode.NOT_FOUND)

            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum" +
                "&temperature_unit=$tempUnit&wind_speed_unit=kmh&timezone=auto&forecast_days=1"

            try {
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (!resp.isSuccessful) return@withContext ToolResult.Error(
                    "Weather API returned ${resp.code}", ToolResult.ErrorCode.NETWORK_ERROR)

                val json    = Json.parseToJsonElement(resp.body!!.string()).jsonObject
                val current = json["current"]?.jsonObject ?: return@withContext ToolResult.Error("No data")
                val daily   = json["daily"]?.jsonObject

                val temp   = current["temperature_2m"]?.jsonPrimitive?.floatOrNull
                val hum    = current["relative_humidity_2m"]?.jsonPrimitive?.intOrNull
                val wind   = current["wind_speed_10m"]?.jsonPrimitive?.floatOrNull
                val wcode  = current["weather_code"]?.jsonPrimitive?.intOrNull
                val maxT   = daily?.get("temperature_2m_max")?.jsonArray?.firstOrNull()?.jsonPrimitive?.floatOrNull
                val minT   = daily?.get("temperature_2m_min")?.jsonArray?.firstOrNull()?.jsonPrimitive?.floatOrNull
                val precip = daily?.get("precipitation_sum")?.jsonArray?.firstOrNull()?.jsonPrimitive?.floatOrNull
                val symbol = units.first().uppercaseChar()

                ToolResult.Success(
                    "Weather for $location: ${weatherDescription(wcode)}\n" +
                    "Temperature: $temp°$symbol (high $maxT°$symbol / low $minT°$symbol)\n" +
                    "Humidity: $hum%  Wind: $wind km/h  Precipitation: $precip mm"
                )
            } catch (e: Exception) {
                ToolResult.Error("Network error: ${e.message}", ToolResult.ErrorCode.NETWORK_ERROR)
            }
        }

    private fun resolveLatLon(location: String): Pair<Double, Double>? {
        val parts = location.split(",").map { it.trim() }
        if (parts.size == 2) {
            val lat = parts[0].toDoubleOrNull()
            val lon = parts[1].toDoubleOrNull()
            if (lat != null && lon != null) return lat to lon
        }
        // Geocode via OpenMeteo geocoding API
        return try {
            val encoded = java.net.URLEncoder.encode(location, "UTF-8")
            val url  = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1"
            val resp = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            val json = Json.parseToJsonElement(resp.body!!.string()).jsonObject
            val first = json["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val lat = first["latitude"]?.jsonPrimitive?.double ?: return null
            val lon = first["longitude"]?.jsonPrimitive?.double ?: return null
            lat to lon
        } catch (_: Exception) { null }
    }

    private fun weatherDescription(code: Int?) = when (code) {
        0 -> "Clear sky"; in 1..3 -> "Partly cloudy"; in 45..48 -> "Foggy"
        in 51..57 -> "Drizzle"; in 61..67 -> "Rain"; in 71..77 -> "Snow"
        in 80..82 -> "Showers"; in 95..99 -> "Thunderstorm"
        else -> "Unknown"
    }
}
