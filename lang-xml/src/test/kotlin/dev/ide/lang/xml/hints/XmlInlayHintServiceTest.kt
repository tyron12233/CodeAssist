package dev.ide.lang.xml.hints

import dev.ide.lang.dom.TextRange
import dev.ide.lang.xml.FakeFile
import dev.ide.lang.xml.parse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlInlayHintServiceTest {

    @Test
    fun showsResolvedValueAfterALocalReference() = runTest {
        val xml = """<TextView android:text="@string/app_name" android:src="@drawable/ic" android:hint="@android:string/ok"/>"""
        val parsed = parse(xml)
        val resolver = XmlResourceValueResolver { rClass, name ->
            if (rClass == "string" && name == "app_name") "CodeAssist" else null // drawable = file (no value); framework skipped
        }
        val hints = XmlInlayHintService({ parsed }, resolver).hints(FakeFile("res/layout/a.xml"), TextRange(0, xml.length))

        assertEquals(1, hints.size, "only the resolvable local value reference gets a hint")
        assertEquals("CodeAssist", hints[0].parts.single().text)
        assertEquals('"', xml[hints[0].offset - 1], "anchored just past the value's closing quote")
        assertTrue(hints[0].paddingLeft)
    }

    @Test
    fun cappsLongValuesAndSkipsBlankOnes() = runTest {
        val xml = """<TextView android:text="@string/long" android:hint="@string/blank"/>"""
        val parsed = parse(xml)
        val resolver = XmlResourceValueResolver { _, name ->
            when (name) { "long" -> "x".repeat(80); "blank" -> "   "; else -> null }
        }
        val hints = XmlInlayHintService({ parsed }, resolver).hints(FakeFile("res/layout/a.xml"), TextRange(0, xml.length))
        // The blank value yields no hint; the long one is capped with an ellipsis.
        assertEquals(1, hints.size)
        assertTrue(hints[0].parts.single().text.endsWith("…") && hints[0].parts.single().text.length <= 30)
    }
}
