# Weather

An interactive console weather report in Kotlin: type a city, get its forecast.

## What it does

At the `>` prompt, enter a city (`london`, `tokyo`, or `cairo`) and it prints a 3-day forecast. To keep the
sample self-contained (and runnable with no network), the data is **bundled sample data**. Type `quit` (or end
input) to stop.

## Structure

- `Forecast.kt` — the `DayForecast` model, a `WeatherService` that looks up a city's forecast, and a `format`
  function that turns one day into a line of text.
- `Main.kt` — an interactive loop: read a city, print its forecast (or a helpful message for an unknown one).

## Run it

Press **Run**, then type a city:

```
Weather — enter a city (london, tokyo, cairo), or 'quit'.
> london
3-day forecast for london:
  Mon: Cloudy, 11-18°C
  Tue: Rain, 10-17°C
  Wed: Sunny, 12-19°C
> quit
Bye!
```

## Make it real

Swap `WeatherService.forecast(city)` for a real request:

1. Add an HTTP client dependency and fetch a forecast (as JSON) for the given city.
2. Parse the response into `DayForecast` objects.
3. Return them.

The command loop and `format` are untouched — they only depend on the `DayForecast` model.
