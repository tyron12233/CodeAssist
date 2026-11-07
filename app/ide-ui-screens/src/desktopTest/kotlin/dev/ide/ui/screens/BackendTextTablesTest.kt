package dev.ide.ui.screens

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The UI finds and reads its backend-text tables the way the app does: through the Compose resources. */
class BackendTextTablesTest {

    @Test
    fun theArabicTableLoadsFromTheResources() = runBlocking {
        val ar = BackendTextTables.load("ar")
        assertTrue(ar.entries.size > 1000, "the Arabic table should load, got ${ar.entries.size} entries")
        assertTrue(ar.translate("Kotlin Console App")!!.any { it in '؀'..'ۿ' })
        Unit
    }

    @Test
    fun aLanguageWithoutATableShowsTheEnglish() = runBlocking {
        assertTrue(BackendTextTables.load("en").isEmpty)
        assertEquals("Kotlin Console App", BackendTextTables.load("xx").tr("Kotlin Console App"))
    }

    @Test
    fun callsInPlainArabicTextStayLeftToRight() {
        val lri = '\u2066'; val pdi = '\u2069'
        assertEquals("نقطة دخول ${lri}main()$pdi.", isolateLatinCalls("نقطة دخول main()."))
        assertEquals("مصفوفة ${lri}int[]$pdi و`run()`", isolateLatinCalls("مصفوفة int[] و`run()`"))
        assertEquals("بلا أقواس", isolateLatinCalls("بلا أقواس"))
    }

    @Test
    fun inlineCodeSpansAreIsolatedForRtl() {
        assertEquals("استخدم `\u2066println(...)\u2069` هنا", isolateCodeSpans("استخدم `println(...)` هنا"))
    }
}
