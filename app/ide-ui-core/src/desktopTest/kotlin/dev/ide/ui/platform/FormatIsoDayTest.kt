package dev.ide.ui.platform

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatIsoDayTest {
    private lateinit var saved: Locale

    @BeforeTest
    fun useEnglish() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @AfterTest
    fun restore() {
        Locale.setDefault(saved)
    }

    @Test
    fun writesTheWeekdayMonthAndDay() {
        assertEquals("Thursday, November 19", formatIsoDay("2026-11-19"))
    }

    @Test
    fun shortFormIsTheMonthAndDay() {
        assertEquals("Nov 19", formatIsoDay("2026-11-19", short = true))
    }

    @Test
    fun followsTheLanguage() {
        Locale.setDefault(Locale.forLanguageTag("es"))
        assertEquals("jueves, noviembre 19", formatIsoDay("2026-11-19"))
    }

    @Test
    fun textThatIsNotADateComesBackUnchanged() {
        assertEquals("", formatIsoDay(""))
        assertEquals("soon", formatIsoDay("soon"))
    }
}
