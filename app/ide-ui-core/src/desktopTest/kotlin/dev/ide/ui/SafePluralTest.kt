package dev.ide.ui

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** [repairEmptyDefaultLocale] puts back a default locale with no language (the one Compose's plurals throw on). */
class SafePluralTest {

    @Test
    fun anEmptyDefaultLocaleIsRepaired() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            repairEmptyDefaultLocale()
            assertEquals("en", Locale.getDefault().language)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun aRealLocaleIsLeftAlone() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("id-ID"))
            repairEmptyDefaultLocale()
            assertEquals(Locale.forLanguageTag("id-ID"), Locale.getDefault())
        } finally {
            Locale.setDefault(original)
        }
    }
}
