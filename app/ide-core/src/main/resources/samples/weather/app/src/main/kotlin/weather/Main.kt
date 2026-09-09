package weather

/**
 * An interactive weather lookup: type a city at the `>` prompt to see its 3-day forecast. Type `quit`
 * (or send end-of-input) to stop. Unknown cities print the list of ones we have data for.
 */
fun main() {
    println("Weather — enter a city (${WeatherService.cities().joinToString(", ")}), or 'quit'.")

    while (true) {
        print("> ")
        System.out.flush() // show the prompt before we block on input
        val city = readLine()?.trim() ?: break // end of input

        if (city.isEmpty()) continue
        if (city.equals("quit", ignoreCase = true) || city.equals("exit", ignoreCase = true)) break

        val forecast = WeatherService.forecast(city)
        if (forecast == null) {
            println("No data for \"$city\". Try: ${WeatherService.cities().joinToString(", ")}")
        } else {
            println("3-day forecast for $city:")
            for (day in forecast) println("  " + format(day))
        }
    }

    println("Bye!")
}
