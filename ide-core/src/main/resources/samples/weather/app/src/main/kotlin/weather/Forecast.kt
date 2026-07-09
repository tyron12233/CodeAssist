package weather

/** One day's forecast. Temperatures are in degrees Celsius. */
data class DayForecast(val day: String, val high: Int, val low: Int, val condition: String)

/**
 * Supplies forecasts by city from **bundled sample data**, so it runs anywhere with no network.
 *
 * To make it real, replace [forecast] with a weather API call: fetch JSON over HTTP for the city, parse it
 * into `DayForecast`s, and return those. Everything else — the formatting and the command loop — stays the
 * same, because they only depend on the `DayForecast` model, not on where it came from.
 */
object WeatherService {
    private val data = mapOf(
        "london" to listOf(
            DayForecast("Mon", 18, 11, "Cloudy"),
            DayForecast("Tue", 17, 10, "Rain"),
            DayForecast("Wed", 19, 12, "Sunny"),
        ),
        "tokyo" to listOf(
            DayForecast("Mon", 26, 19, "Sunny"),
            DayForecast("Tue", 24, 18, "Cloudy"),
            DayForecast("Wed", 22, 17, "Rain"),
        ),
        "cairo" to listOf(
            DayForecast("Mon", 34, 22, "Sunny"),
            DayForecast("Tue", 35, 23, "Sunny"),
            DayForecast("Wed", 33, 21, "Clear"),
        ),
    )

    /** The 3-day forecast for [city] (case-insensitive), or null if there's no data for it. */
    fun forecast(city: String): List<DayForecast>? = data[city.trim().lowercase()]

    /** The cities this sample has data for. */
    fun cities(): List<String> = data.keys.toList()
}

/** A one-line summary of [forecast], e.g. `Mon: Sunny, 11-18°C`. */
fun format(forecast: DayForecast): String =
    "${forecast.day}: ${forecast.condition}, ${forecast.low}-${forecast.high}°C"
