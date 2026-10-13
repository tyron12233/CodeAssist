package dev.ide.android.support.navigation

import dev.ide.android.support.navigation.ResourceOccurrence.Kind
import dev.ide.android.support.resources.ResourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOccurrencesTest {

    private fun texts(text: String, occ: List<ResourceOccurrence>) = occ.map { text.substring(it.range.first, it.range.last + 1) to it.kind }

    @Test
    fun xmlReferencesAndDeclarations() {
        val values = """
            <resources>
                <string name="title">Hi</string>
                <string name="other">@string/title</string>
                <item type="string" name="title2">x</item>
            </resources>
        """.trimIndent()
        val occ = ResourceOccurrences.inXml(values, ResourceType.STRING, "title", isValuesFile = true)
        assertEquals(listOf("title" to Kind.DECLARATION, "title" to Kind.REFERENCE), texts(values, occ))

        val layout = """<TextView android:text="@string/title" android:hint="@android:string/title" android:tag="@color/title"/>"""
        val inLayout = ResourceOccurrences.inXml(layout, ResourceType.STRING, "title", isValuesFile = false)
        assertEquals(1, inLayout.size, "framework and other-type references are not ours")
    }

    @Test
    fun idDeclarationVersusReference() {
        val layout = """
            <View android:id="@+id/header"/>
            <View app:layout_constraintTop_toBottomOf="@+id/header" android:labelFor="@id/header"/>
        """.trimIndent()
        val kinds = ResourceOccurrences.inXml(layout, ResourceType.ID, "header", false).map { it.kind }
        assertEquals(listOf(Kind.DECLARATION, Kind.REFERENCE, Kind.REFERENCE), kinds)
    }

    @Test
    fun styleParentsExplicitAndImplicit() {
        val values = """
            <resources>
                <style name="Theme.App" parent="Theme.Material3.DayNight"/>
                <style name="Theme.App.Dark"/>
                <style name="Other" parent="Theme.App"/>
                <style name="Third" parent="@style/Theme.App"/>
            </resources>
        """.trimIndent()
        val occ = ResourceOccurrences.inXml(values, ResourceType.STYLE, "Theme_App", isValuesFile = true)
        assertEquals(
            listOf(
                "Theme.App" to Kind.DECLARATION,
                "Theme.App" to Kind.IMPLICIT_PARENT,
                "Theme.App" to Kind.REFERENCE,
                "Theme.App" to Kind.REFERENCE,
            ),
            texts(values, occ),
        )
    }

    @Test
    fun customAttributeUses() {
        val layout = """<com.x.Chart app:lineColor="#f00" android:lineColor="x" tools:lineColor="y"/>"""
        assertEquals(1, ResourceOccurrences.inXml(layout, ResourceType.ATTR, "lineColor", false).size)
        val values = """<resources><attr name="lineColor" format="color"/><style name="S"><item name="lineColor">#fff</item><item name="android:lineColor">x</item></style><color name="c">?attr/lineColor</color></resources>"""
        val kinds = ResourceOccurrences.inXml(values, ResourceType.ATTR, "lineColor", true).map { it.kind }
        assertEquals(listOf(Kind.DECLARATION, Kind.REFERENCE, Kind.REFERENCE), kinds)
    }

    @Test
    fun codeReferencesAndBindings() {
        val code = """
            import com.example.databinding.ActivityMainBinding
            val a = R.layout.activity_main
            val b = com.example.R.layout.activity_main
            val c = android.R.layout.activity_main
            val d = R.layout.activity_main2
            lateinit var binding: ActivityMainBinding
            val e = ActivityMainBindingX()
        """.trimIndent()
        val occ = ResourceOccurrences.inCode(code, ResourceType.LAYOUT, "activity_main")
        assertEquals(
            listOf(
                "ActivityMainBinding" to Kind.BINDING,
                "activity_main" to Kind.REFERENCE,
                "activity_main" to Kind.REFERENCE,
                "ActivityMainBinding" to Kind.BINDING,
            ),
            texts(code, occ),
        )
        assertEquals(listOf("activity_main"), ResourceOccurrences.bindingLayoutsIn(code, listOf("activity_main", "item_row")))
        assertTrue(ResourceType.LAYOUT to "activity_main2" in ResourceOccurrences.referencedInCode(code))
    }

    @Test
    fun codeReferenceAtCaret() {
        val code = "setContentView(R.layout.activity_main)\nval x = android.R.string.ok"
        val t = ResourceOccurrences.codeReferenceAt(code, code.indexOf("activity"))!!
        assertEquals(ResourceType.LAYOUT, t.type)
        assertEquals("activity_main", code.substring(t.range.first, t.range.last + 1))
        assertEquals(null, t.packageName)
        assertEquals("android", ResourceOccurrences.codeReferenceAt(code, code.indexOf("ok"))!!.packageName)
        assertNull(ResourceOccurrences.codeReferenceAt(code, code.indexOf("setContent")))
    }

    @Test
    fun idBindingFieldsOnlyWhereTheBindingIsUsed() {
        val code = """
            class Home {
                lateinit var binding: ActivityMainBinding
                fun bind() {
                    binding.headerTitle.text = "x"
                    binding?.headerTitle?.isVisible = true
                    headerTitle()
                    val headerTitle = 1
                }
            }
        """.trimIndent()
        val occ = ResourceOccurrences.inCode(code, ResourceType.ID, "header_title", idBindings = listOf("ActivityMainBinding"))
        assertEquals(listOf(Kind.BINDING_FIELD, Kind.BINDING_FIELD), occ.map { it.kind }, "member accesses only, no call or local")
        val other = ResourceOccurrences.inCode(code, ResourceType.ID, "header_title", idBindings = listOf("ItemRowBinding"))
        assertTrue(other.isEmpty(), "a file not using the declaring layout's binding has no field uses")
        assertEquals("headerTitle", ResourceOccurrences.bindingFieldName("header_title"))
    }

    @Test
    fun keyedScanCarriesEveryResourceAFileNames() {
        val layout = """<LinearLayout app:barColor="@color/primary" tools:context=".Main"><TextView android:text="@string/title"/></LinearLayout>"""
        val keys = ResourceOccurrences.allInXml(layout, isValuesFile = false).map { it.key }.toSet()
        assertEquals(setOf("attr/barColor", "color/primary", "string/title"), keys)
        val code = "val b: ActivityMainBinding = TODO(); val s = R.string.title; val f = android.R.string.ok"
        val codeKeys = ResourceOccurrences.allInCode(code).map { it.key }.toSet()
        assertEquals(setOf("binding/ActivityMainBinding", "string/title"), codeKeys)
    }

    @Test
    fun bindingClassNames() {
        assertEquals("ActivityMainBinding", ResourceOccurrences.bindingClassName("activity_main"))
        assertEquals("ItemRow2Binding", ResourceOccurrences.bindingClassName("item_row2"))
    }
}
