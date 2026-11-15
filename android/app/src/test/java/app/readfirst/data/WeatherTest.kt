package app.readfirst.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {

    private fun day(date: String, max: Int, min: Int, rains: List<Int>, noonDesc: String = "Sunny"): String {
        val times = listOf("0", "300", "600", "900", "1200", "1500", "1800", "2100")
        val hours = times.mapIndexed { i, t ->
            val desc = if (t == "1200") noonDesc else "Clear"
            """{"time":"$t","tempC":"${20 + i}","weatherDesc":[{"value":"$desc"}],"chanceofrain":"${rains[i]}"}"""
        }.joinToString(",")
        return """{"date":"$date","maxtempC":"$max","mintempC":"$min","hourly":[$hours]}"""
    }

    private val sample = """
    {
      "current_condition":[{
        "FeelsLikeC":"31","humidity":"62","localObsDateTime":"2026-11-15 10:23 AM",
        "temp_C":"28","uvIndex":"6","windspeedKmph":"12","winddir16Point":"NW",
        "weatherDesc":[{"value":"Partly cloudy"}]
      }],
      "nearest_area":[{
        "areaName":[{"value":"Pune"}],"region":[{"value":"Maharashtra"}],"country":[{"value":"India"}]
      }],
      "weather":[
        ${day("2026-11-15", 32, 21, listOf(0, 0, 0, 5, 10, 20, 60, 30), "Light rain shower")},
        ${day("2026-11-16", 33, 22, listOf(0, 0, 0, 0, 0, 0, 0, 0))},
        ${day("2026-11-17", 30, 20, listOf(10, 20, 30, 40, 50, 90, 40, 10), "Heavy rain")}
      ]
    }
    """.trimIndent()

    @Test fun parsesCurrentConditions() {
        val d = Weather.parseDetail(sample, 10 * 60, "2026-11-15")!!
        assertEquals("Pune, Maharashtra", d.place)
        assertEquals("Partly cloudy", d.condition)
        assertEquals(28, d.tempC)
        assertEquals(31, d.feelsC)
        assertEquals(62, d.humidity)
        assertEquals(12, d.windKmph)
        assertEquals("NW", d.windDir)
        assertEquals(6, d.uv)
        assertEquals("10:23 AM", d.observed)
    }

    @Test fun nextSixHoursFromNow() {
        val d = Weather.parseDetail(sample, 10 * 60, "2026-11-15")!!
        // 12:00, 15:00, 18:00, 21:00 today, then 00:00 and 03:00 tomorrow.
        assertEquals(listOf(720, 900, 1080, 1260, 0, 180), d.hours.map { it.minutes })
        assertEquals(24, d.hours[0].tempC)
        assertEquals("Light rain shower", d.hours[0].condition)
        assertEquals(10, d.hours[0].rainPct)
    }

    @Test fun lateEveningRollsIntoTomorrow() {
        val d = Weather.parseDetail(sample, 22 * 60, "2026-11-15")!!
        assertEquals(listOf(0, 180, 360, 540, 720, 900), d.hours.map { it.minutes })
    }

    @Test fun midnightEntryIsIncludedAtExactlyMidnight() {
        val d = Weather.parseDetail(sample, 0, "2026-11-15")!!
        assertEquals(0, d.hours.first().minutes)
    }

    @Test fun todayMissingFromDataStartsAtFirstLaterDay() {
        val d = Weather.parseDetail(sample, 10 * 60, "2026-11-16")!!
        assertEquals(720, d.hours.first().minutes)
        assertEquals("2026-11-16", d.days.first().date)
        assertEquals(2, d.days.size)
    }

    @Test fun threeDaysWithNoonConditionAndMaxRain() {
        val d = Weather.parseDetail(sample, 10 * 60, "2026-11-15")!!
        assertEquals(3, d.days.size)
        val first = d.days[0]
        assertEquals("2026-11-15", first.date)
        assertEquals(32, first.maxC)
        assertEquals(21, first.minC)
        assertEquals("Light rain shower", first.condition)
        assertEquals(60, first.rainPct)
        assertEquals("Sunny", d.days[1].condition)
        assertEquals(0, d.days[1].rainPct)
        assertEquals("Heavy rain", d.days[2].condition)
        assertEquals(90, d.days[2].rainPct)
    }

    @Test fun allFieldsAreOptional() {
        val d = Weather.parseDetail("""{"current_condition":[{}]}""", 600, "2026-11-15")!!
        assertEquals("", d.place)
        assertEquals("", d.condition)
        assertNull(d.tempC)
        assertNull(d.feelsC)
        assertNull(d.humidity)
        assertNull(d.windKmph)
        assertNull(d.uv)
        assertEquals("", d.observed)
        assertTrue(d.hours.isEmpty())
        assertTrue(d.days.isEmpty())
    }

    @Test fun forecastWithoutCurrentConditionStillParses() {
        val json = """{"weather":[${day("2026-11-15", 30, 20, List(8) { 0 })}]}"""
        val d = Weather.parseDetail(json, 600, "2026-11-15")!!
        assertNull(d.tempC)
        assertEquals(1, d.days.size)
    }

    @Test fun nonNumericValuesBecomeNull() {
        val json = """{"current_condition":[{"temp_C":"n/a","humidity":"","uvIndex":"high"}]}"""
        val d = Weather.parseDetail(json, 600, "2026-11-15")!!
        assertNull(d.tempC)
        assertNull(d.humidity)
        assertNull(d.uv)
    }

    @Test fun malformedInputGivesNull() {
        assertNull(Weather.parseDetail("", 600, "2026-11-15"))
        assertNull(Weather.parseDetail("not json at all", 600, "2026-11-15"))
        assertNull(Weather.parseDetail("{}", 600, "2026-11-15"))
        assertNull(Weather.parseDetail("[1,2,3]", 600, "2026-11-15"))
        assertNull(Weather.parseDetail("""{"current_condition":[],"weather":[]}""", 600, "2026-11-15"))
        assertNull(Weather.parseDetail("""{"current_condition":"oops"}""", 600, "2026-11-15"))
    }

    @Test fun malformedPartsAreSkippedNotFatal() {
        val json = """{"current_condition":[{"temp_C":"20"}],"weather":[42,{"date":"2026-11-15","hourly":["x",{"time":"abc"},{"time":"2500"},{"time":"1200","tempC":"25"}]}]}"""
        val d = Weather.parseDetail(json, 600, "2026-11-15")!!
        assertEquals(20, d.tempC)
        assertEquals(1, d.hours.size)
        assertEquals(720, d.hours[0].minutes)
        assertEquals(25, d.hours[0].tempC)
        assertNotNull(d.days.single())
    }

    @Test fun placeAvoidsDuplicates() {
        assertEquals("Pune, Maharashtra", Weather.shortPlace("Pune", "Maharashtra", "India"))
        assertEquals("Singapore", Weather.shortPlace("Singapore", "Singapore", "Singapore"))
        assertEquals("Pune", Weather.shortPlace("Pune", "", "India"))
        assertEquals("Maharashtra", Weather.shortPlace("", "Maharashtra", "India"))
        assertEquals("India", Weather.shortPlace(" ", " ", "India"))
        assertEquals("", Weather.shortPlace("", "", ""))
    }

    @Test fun googleUrlEncodesThePlace() {
        assertEquals(
            "https://www.google.com/search?q=10+day+weather+forecast+Pune%2C+Maharashtra",
            Weather.googleForecastUrl("Pune, Maharashtra"),
        )
        assertEquals(
            "https://www.google.com/search?q=10+day+weather+forecast+S%C3%A3o+Paulo",
            Weather.googleForecastUrl("São Paulo"),
        )
    }

    @Test fun googleUrlWithoutPlace() {
        assertEquals("https://www.google.com/search?q=10+day+weather+forecast", Weather.googleForecastUrl(""))
        assertEquals("https://www.google.com/search?q=10+day+weather+forecast", Weather.googleForecastUrl("   "))
    }
}
