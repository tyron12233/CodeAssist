package dev.ide.lang.xml.structure

import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.xml.XmlTreeParser
import kotlin.test.Test
import kotlin.test.assertEquals

class XmlStructureTest {

    private fun structure(text: String) = XmlStructure.of(XmlTreeParser.parse("/p/res/layout/a.xml", text))

    @Test
    fun layoutElementsAreNamedByTagAndId() {
        val text = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:id="@+id/title"/>
                <com.example.Chart/>
            </LinearLayout>
        """.trimIndent()
        val items = structure(text)
        assertEquals(listOf("LinearLayout", "TextView", "Chart"), items.map { it.name })
        assertEquals(listOf(null, "title", null), items.map { it.detail })
        assertEquals(listOf(0, 1, 1), items.map { it.depth })
        assertEquals(listOf(SymbolKind.CLASS, SymbolKind.FIELD, SymbolKind.FIELD), items.map { it.kind })
        assertEquals(text.indexOf("TextView"), items[1].nameOffset)
    }

    @Test
    fun declarationsAreNamedByTheirName() {
        val items = structure("""<resources><string name="app_name">X</string><color name="primary">#fff</color></resources>""")
        assertEquals(listOf("resources", "app_name", "primary"), items.map { it.name })
        assertEquals(listOf(null, "string", "color"), items.map { it.detail })
    }
}
