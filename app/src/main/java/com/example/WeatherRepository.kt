package com.example

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WeatherRepository(private val context: Context) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("ambient_prefs", Context.MODE_PRIVATE)

    private val _weatherState = MutableStateFlow<WeatherState?>(loadCachedWeather())
    val weatherState: StateFlow<WeatherState?> = _weatherState

    companion object {
        const val TAG = "WeatherRepository"
        const val LAT = 11.7895069
        const val LON = 75.4908824
        const val LOCATION_LABEL = "Thalassery"
        const val TIMEZONE = "Asia/Kolkata"
    }

    private fun loadCachedWeather(): WeatherState? {
        val json = prefs.getString("weather_cache", null) ?: return null
        return try {
            gson.fromJson(json, WeatherState::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cached weather", e)
            null
        }
    }

    private fun cacheWeather(state: WeatherState) {
        try {
            val json = gson.toJson(state)
            prefs.edit().putString("weather_cache", json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error caching weather", e)
        }
    }

    fun getApiKey(): String {
        // First check SharedPreferences
        val savedKey = prefs.getString("owm_api_key", "") ?: ""
        if (savedKey.trim().isNotEmpty()) {
            return savedKey.trim()
        }
        // Fallback to BuildConfig if available
        val configKey = try {
            val field = BuildConfig::class.java.getField("OPENWEATHER_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
        if (configKey.trim().isNotEmpty() && !configKey.startsWith("MY_OPENWEATHER") && !configKey.contains("placeholder", ignoreCase = true)) {
            return configKey.trim()
        }
        // Fallback to user's hardcoded personal key
        return "AIzaSyCZGW15NUKSfCmX08fwMxmlceylpKr3u08"
    }

    private fun calculateMoonPhase(timeMs: Long): Float {
        val baseMs = 947182440000L // New moon Jan 6, 2000 18:14 UTC
        val synodicMonthMs = 29.530588853 * 24 * 60 * 60 * 1000L
        val diffMs = timeMs - baseMs
        var phase = (diffMs % synodicMonthMs) / synodicMonthMs
        if (phase < 0) phase += 1.0
        return phase.toFloat()
    }

    private fun mapWmoToOwmCode(wmoCode: Int): Int {
        return when (wmoCode) {
            0 -> 800     // Clear sky
            1 -> 801     // Mainly clear
            2 -> 802     // Partly cloudy
            3 -> 804     // Overcast
            45, 48 -> 741 // Fog
            51, 53, 55 -> 300 // Drizzle
            56, 57 -> 611 // Freezing drizzle -> Sleet
            61 -> 500    // Light rain
            63 -> 501    // Moderate rain
            65 -> 502    // Heavy rain
            66, 67 -> 615 // Freezing rain
            71 -> 600    // Light snow
            73 -> 601    // Snow
            75 -> 602    // Heavy snow
            77 -> 621    // Snow grains
            80 -> 520    // Light shower rain
            81 -> 521    // Shower rain
            82 -> 522    // Heavy shower rain
            85, 86 -> 621 // Snow showers
            95 -> 211    // Thunderstorm
            96, 99 -> 232 // Thunderstorm with hail
            else -> 800
        }
    }

    private fun mapWmoToDesc(wmoCode: Int): String {
        return when (wmoCode) {
            0 -> "Clear Sky"
            1 -> "Mainly Clear"
            2 -> "Partly Cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing Drizzle"
            61 -> "Light Rain"
            63 -> "Moderate Rain"
            65 -> "Heavy Rain"
            66, 67 -> "Freezing Rain"
            71 -> "Light Snow"
            73 -> "Moderate Snow"
            75 -> "Heavy Snow"
            77 -> "Snow Grains"
            80 -> "Light Showers"
            81 -> "Rain Showers"
            82 -> "Heavy Showers"
            85, 86 -> "Snow Showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Clear"
        }
    }

    suspend fun fetchWeather(): String? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,cloud_cover,rain,visibility,weather_code&hourly=temperature_2m,precipitation_probability,precipitation,wind_speed_10m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code,sunrise,sunset&timezone=Asia%2FKolkata&timeformat=unixtime&forecast_days=7&past_days=1"
        Log.d(TAG, "Fetching weather from Open-Meteo: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    Log.e(TAG, "Unsuccessful Open-Meteo response: $code")
                    return@withContext "Sync failed with Open-Meteo error code $code."
                }

                val bodyString = response.body?.string() ?: return@withContext "Empty response received from weather server."
                val root = JsonParser.parseString(bodyString).asJsonObject

                val currentObj = root.getAsJsonObject("current")
                val temp = currentObj.get("temperature_2m").asFloat
                val feelsLike = currentObj.get("apparent_temperature").asFloat
                val humidity = currentObj.get("relative_humidity_2m").asInt
                val windSpeedKmh = currentObj.get("wind_speed_10m").asFloat
                val windSpeed = windSpeedKmh / 3.6f // Convert km/h to m/s
                val cloudCover = currentObj.get("cloud_cover").asInt
                val visibility = currentObj.get("visibility").asInt
                val rawWmoCode = currentObj.get("weather_code").asInt

                val condId = mapWmoToOwmCode(rawWmoCode)
                val desc = mapWmoToDesc(rawWmoCode)

                val rain = if (currentObj.has("rain")) {
                    currentObj.get("rain").asFloat
                } else {
                    0f
                }

                val dailyObj = root.getAsJsonObject("daily")
                var high = temp
                var low = temp
                var sunrise = System.currentTimeMillis() / 1000L - 12 * 3600
                var sunset = System.currentTimeMillis() / 1000L + 12 * 3600

                if (dailyObj != null) {
                    val maxArr = dailyObj.getAsJsonArray("temperature_2m_max")
                    val todayIdx = if (maxArr != null && maxArr.size() > 1) 1 else 0
                    if (maxArr != null && maxArr.size() > todayIdx) {
                        high = maxArr.get(todayIdx).asFloat
                    }
                    val minArr = dailyObj.getAsJsonArray("temperature_2m_min")
                    if (minArr != null && minArr.size() > todayIdx) {
                        low = minArr.get(todayIdx).asFloat
                    }
                    val sunriseArr = dailyObj.getAsJsonArray("sunrise")
                    if (sunriseArr != null && sunriseArr.size() > todayIdx) {
                        sunrise = sunriseArr.get(todayIdx).asLong
                    }
                    val sunsetArr = dailyObj.getAsJsonArray("sunset")
                    if (sunsetArr != null && sunsetArr.size() > todayIdx) {
                        sunset = sunsetArr.get(todayIdx).asLong
                    }
                }

                val moonPhaseVal = calculateMoonPhase(System.currentTimeMillis())

                val hourlyObj = root.getAsJsonObject("hourly")
                val hourlyTempsList = mutableListOf<Float>()
                val hourlyList = mutableListOf<HourlyWeather>()
                val dailyList = mutableListOf<DailyWeather>()

                // Current time calculation
                val currentEpoch = System.currentTimeMillis() / 1000L

                // 1. Parsing detailed Hourly list
                if (hourlyObj != null) {
                    val hTime = hourlyObj.getAsJsonArray("time")
                    val hTemp = hourlyObj.getAsJsonArray("temperature_2m")
                    val hPrecipProb = if (hourlyObj.has("precipitation_probability")) hourlyObj.getAsJsonArray("precipitation_probability") else null
                    val hPrecip = if (hourlyObj.has("precipitation")) hourlyObj.getAsJsonArray("precipitation") else null
                    val hWind = if (hourlyObj.has("wind_speed_10m")) hourlyObj.getAsJsonArray("wind_speed_10m") else null
                    val hCode = if (hourlyObj.has("weather_code")) hourlyObj.getAsJsonArray("weather_code") else null
                    val hIsDay = if (hourlyObj.has("is_day")) hourlyObj.getAsJsonArray("is_day") else null

                    if (hTime != null) {
                        for (i in 0 until hTime.size()) {
                            val epoch = hTime.get(i).asLong
                            val tValue = hTemp?.get(i)?.asFloat ?: temp
                            val pProb = hPrecipProb?.get(i)?.asInt ?: 0
                            val pValue = hPrecip?.get(i)?.asFloat ?: 0f
                            val wValue = hWind?.get(i)?.asFloat ?: 0f
                            val wmoCode = hCode?.get(i)?.asInt ?: 0
                            val cond = mapWmoToOwmCode(wmoCode)
                            val dayVal = hIsDay?.get(i)?.asInt ?: 1
                            val isD = dayVal == 1

                            hourlyList.add(HourlyWeather(
                                epoch = epoch,
                                tempC = tValue,
                                precipitationProb = pProb,
                                precipitationMm = pValue,
                                windSpeedKmh = wValue,
                                conditionId = cond,
                                isDay = isD
                            ))
                        }
                    }

                    // Build standard 12h list for overlay surface clock indicator matching legacy signature
                    var startIndex = 0
                    if (hTime != null && hTime.size() > 0) {
                        for (i in 0 until hTime.size()) {
                            val epoch = hTime.get(i).asLong
                            if (epoch >= currentEpoch - 1800) {
                                startIndex = i
                                break
                            }
                        }
                    }
                    if (hTemp != null && hTemp.size() > 0) {
                        for (i in 0 until 12) {
                            val idx = (startIndex + i) % hTemp.size()
                            hourlyTempsList.add(hTemp.get(idx).asFloat)
                        }
                    }
                }

                if (hourlyTempsList.isEmpty()) {
                    for (i in 1..12) hourlyTempsList.add(temp)
                }

                // 2. Parsing detailed Daily list
                if (dailyObj != null) {
                    val dailyTime = dailyObj.getAsJsonArray("time")
                    val dailyMax = dailyObj.getAsJsonArray("temperature_2m_max")
                    val dailyMin = dailyObj.getAsJsonArray("temperature_2m_min")
                    val dailyCode = dailyObj.getAsJsonArray("weather_code")
                    val dailyPrecipProb = if (dailyObj.has("precipitation_probability_max")) dailyObj.getAsJsonArray("precipitation_probability_max") else null
                    val dailySunrise = dailyObj.getAsJsonArray("sunrise")
                    val dailySunset = dailyObj.getAsJsonArray("sunset")

                    if (dailyTime != null) {
                        for (i in 0 until dailyTime.size()) {
                            val epoch = dailyTime.get(i).asLong
                            val tMax = dailyMax?.get(i)?.asFloat ?: temp
                            val tMin = dailyMin?.get(i)?.asFloat ?: temp
                            val wmoCode = dailyCode?.get(i)?.asInt ?: 0
                            val cond = mapWmoToOwmCode(wmoCode)
                            val description = mapWmoToDesc(wmoCode)
                            val pProb = dailyPrecipProb?.get(i)?.asInt ?: 0
                            val sRise = dailySunrise?.get(i)?.asLong ?: (epoch + 6 * 3600)
                            val sSet = dailySunset?.get(i)?.asLong ?: (epoch + 18 * 3600)

                            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
                            cal.timeInMillis = epoch * 1000L

                            val dfDay = SimpleDateFormat("EEE", Locale.US).format(cal.time).uppercase()
                            val dfDate = SimpleDateFormat("MMM d", Locale.US).format(cal.time).uppercase()

                            val todayCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
                            val isToday = cal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR) &&
                                          cal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR)

                            val yesterdayCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
                            yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
                            val isYesterday = cal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR) &&
                                              cal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR)

                            val dayLabel = when {
                                isToday -> "TODAY"
                                isYesterday -> "YEST"
                                else -> dfDay
                            }

                            dailyList.add(DailyWeather(
                                epoch = epoch,
                                tempMaxC = tMax,
                                tempMinC = tMin,
                                precipitationProbMax = pProb,
                                conditionId = cond,
                                description = description,
                                dayLabel = dayLabel,
                                dateLabel = dfDate,
                                sunriseEpoch = sRise,
                                sunsetEpoch = sSet
                            ))
                        }
                    }
                }

                val state = WeatherState(
                    tempC = temp,
                    feelsLikeC = feelsLike,
                    conditionId = condId,
                    description = desc,
                    humidity = humidity,
                    windSpeed = windSpeed,
                    cloudCover = cloudCover,
                    rainMmH = rain,
                    visibility = visibility,
                    sunriseEpoch = sunrise,
                    sunsetEpoch = sunset,
                    moonPhase = moonPhaseVal,
                    highC = high,
                    lowC = low,
                    hourlyTemps = hourlyTempsList,
                    hourlyList = hourlyList,
                    dailyList = dailyList,
                    fetchedAt = System.currentTimeMillis()
                )

                _weatherState.value = state
                cacheWeather(state)
                Log.d(TAG, "Successfully updated weather with Open-Meteo: $state")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching weather, falling back to cache", e)
            return@withContext "Network or parsing exception: ${e.message}"
        }
    }
}
