package com.ts10.inspector.ui

import com.ts10.inspector.settings.DashboardSettings
import com.ts10.inspector.settings.DateFormatMode
import com.ts10.inspector.settings.SpeedUnit
import com.ts10.inspector.settings.TemperatureUnit
import com.ts10.inspector.settings.TimeFormatMode
import java.time.LocalDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFormatsTest {
    private val moment = LocalDateTime.of(2026, 11, 27, 15, 5)

    @Test
    fun convertsSpeedAndLimitToMph() {
        val mph = DisplayFormats(speedUnit = SpeedUnit.MPH, locale = Locale.US)
        assertEquals(62, mph.speed(100))
        assertEquals("31 mph", mph.speedText(50))
        // 48 km/h es como OSM guarda "30 mph".
        assertEquals(30, mph.speedLimit(48))
        assertEquals(30, mph.speedLimit(50))
        assertEquals(50, DisplayFormats().speedLimit(50))
    }

    @Test
    fun convertsTemperatureToFahrenheit() {
        val f = DisplayFormats(temperatureUnit = TemperatureUnit.FAHRENHEIT, locale = Locale.US)
        assertEquals("68 °F", f.temperatureText(20))
        assertEquals("50°", f.temperatureLabel("10°"))
        assertEquals("55°", f.temperatureLabel("12.5 °C"))
        assertEquals("--°", f.temperatureLabel("--°"))
        assertEquals("12.5 °C", DisplayFormats().temperatureLabel("12.5 °C"))
    }

    @Test
    fun formatsTimeAndDates() {
        val es = DisplayFormats(locale = Locale("es", "ES"))
        assertEquals("15:05", es.time(moment))
        assertEquals("27/11/2026", es.date(moment))
        val us = DisplayFormats(use24Hour = false, dateStyle = DateFormatMode.AMERICAN, locale = Locale.US)
        assertEquals("3:05 PM", us.time(moment))
        assertEquals("11/27/26", us.date(moment, shortYear = true))
        assertEquals("Friday 27", us.dashboardDate(moment))
        val uk = DisplayFormats(dateStyle = DateFormatMode.ENGLISH, locale = Locale.UK)
        assertEquals("27 Nov 2026", uk.date(moment))
    }

    @Test
    fun resolvesAutomaticModes() {
        val auto = DashboardSettings(timeFormat = TimeFormatMode.AUTO, dateFormat = DateFormatMode.AUTO)
        assertEquals(DateFormatMode.AMERICAN, DisplayFormats.resolve(auto, Locale.US, false).dateStyle)
        assertEquals(DateFormatMode.ENGLISH, DisplayFormats.resolve(auto, Locale.UK, true).dateStyle)
        assertEquals(DateFormatMode.SPANISH, DisplayFormats.resolve(auto, Locale("es", "ES"), true).dateStyle)
        assertEquals(false, DisplayFormats.resolve(auto, Locale.US, false).use24Hour)
        val forced = auto.copy(timeFormat = TimeFormatMode.H24)
        assertEquals(true, DisplayFormats.resolve(forced, Locale.US, false).use24Hour)
    }
}
