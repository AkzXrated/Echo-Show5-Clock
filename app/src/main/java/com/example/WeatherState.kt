package com.example

data class HourlyWeather(
  val epoch: Long,
  val tempC: Float,
  val precipitationProb: Int,
  val precipitationMm: Float,
  val windSpeedKmh: Float,
  val conditionId: Int,
  val isDay: Boolean
)

data class DailyWeather(
  val epoch: Long,
  val tempMaxC: Float,
  val tempMinC: Float,
  val precipitationProbMax: Int,
  val conditionId: Int,
  val description: String,
  val dayLabel: String,         // e.g. "MON", "TODAY", "YEST"
  val dateLabel: String,        // e.g. "JUN 8"
  val sunriseEpoch: Long,
  val sunsetEpoch: Long
)

data class WeatherState(
  val tempC: Float,
  val feelsLikeC: Float,
  val conditionId: Int,      // OWM condition code
  val description: String,
  val humidity: Int,
  val windSpeed: Float,
  val cloudCover: Int,       // 0-100%
  val rainMmH: Float,        // mm/h current
  val visibility: Int,       // metres
  val sunriseEpoch: Long,
  val sunsetEpoch: Long,
  val moonPhase: Float,      // 0.0-1.0
  val highC: Float,
  val lowC: Float,
  val hourlyTemps: List<Float>,  // next 12h
  val fetchedAt: Long,
  val hourlyList: List<HourlyWeather> = emptyList(),
  val dailyList: List<DailyWeather> = emptyList()
)
