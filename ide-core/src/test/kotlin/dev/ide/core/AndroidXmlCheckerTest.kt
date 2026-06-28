package dev.ide.core

import dev.ide.android.support.metadata.AndroidSdkMetadata
import dev.ide.android.support.metadata.AttrsXmlParser
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.xml.XmlIncrementalParser
import dev.ide.lang.xml.lint.AttrInfo
import dev.ide.lang.xml.lint.XmlLintRules
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [AndroidXmlChecker] answers the wrong-attribute / wrong-value schema questions from a real
 * [AndroidSdkMetadata] hierarchy (built in-test, so no Android SDK is needed) and stays conservative: it
 * judges only framework `android:` attributes on known widgets, leaves custom/`app:`/cold cases alone, and
 * never treats a mixed-format attribute (`layout_width`) as a closed value set.
 */
class AndroidXmlCheckerTest {

    private val sdk = run {
        val attrs = AttrsXmlParser.parse(
            """<resources>
                 <attr name="text" format="string"/>
                 <attr name="visibility" format="enum">
                   <enum name="visible" value="0"/><enum name="invisible" value="1"/><enum name="gone" value="2"/>
                 </attr>
                 <attr name="id" format="reference"/>
                 <attr name="layout_below" format="reference"/>
                 <attr name="layout_width" format="dimension">
                   <enum name="match_parent" value="-1"/><enum name="wrap_content" value="-2"/>
                 </attr>
                 <attr name="layout_height" format="dimension">
                   <enum name="match_parent" value="-1"/><enum name="wrap_content" value="-2"/>
                 </attr>
                 <declare-styleable name="View"><attr name="id"/><attr name="visibility"/></declare-styleable>
                 <declare-styleable name="TextView"><attr name="text"/></declare-styleable>
                 <declare-styleable name="ViewGroup_Layout"><attr name="layout_width"/><attr name="layout_height"/></declare-styleable>
                 <declare-styleable name="RelativeLayout_Layout"><attr name="layout_below"/></declare-styleable>
                 <declare-styleable name="LinearLayout_Layout"/>
               </resources>"""
        )
        AndroidSdkMetadata(
            34, attrs.attrs, attrs.styleables,
            mapOf("Button" to "TextView", "TextView" to "View", "RelativeLayout" to "ViewGroup",
                "LinearLayout" to "ViewGroup", "ViewGroup" to "View"),
            listOf(
                AndroidSdkMetadata.WidgetInfo("Button", false), AndroidSdkMetadata.WidgetInfo("TextView", false),
                AndroidSdkMetadata.WidgetInfo("RelativeLayout", true), AndroidSdkMetadata.WidgetInfo("LinearLayout", true),
            ),
        )
    }

    private val checker = AndroidXmlChecker(layout = { sdk })
    private val LAYOUT = "res/layout/a.xml"
    private val MANIFEST = "src/main/AndroidManifest.xml"

    @Test
    fun unknownFrameworkAttributeOnKnownWidgetIsNotAllowed() {
        assertEquals(AttrInfo.NotAllowed, checker.describe(LAYOUT, "TextView", null, "android:bogus"))
        // An inherited attribute (View.visibility on a Button, via the hierarchy) is recognized.
        assertIs<AttrInfo.Recognized>(checker.describe(LAYOUT, "Button", null, "android:visibility"))
    }

    @Test
    fun enumAttributeExposesItsClosedValueSet() {
        val info = checker.describe(LAYOUT, "TextView", null, "android:visibility")
        assertIs<AttrInfo.Recognized>(info)
        assertEquals(setOf("visible", "invisible", "gone"), info.allowedValues)
        assertEquals(false, info.isFlag)
    }

    @Test
    fun mixedFormatAttributeIsFreeFormNotAClosedSet() {
        // layout_width accepts a dimension OR an enum → it must never be value-checked (no false "16dp" error).
        val info = checker.describe(LAYOUT, "TextView", "LinearLayout", "android:layout_width")
        assertIs<AttrInfo.Recognized>(info)
        assertNull(info.allowedValues, "a dimension+enum attribute is free-form")
    }

    @Test
    fun customAndNonFrameworkAttributesAreLeftAlone() {
        // Custom view tag → the framework metadata can't judge it.
        assertEquals(AttrInfo.Indeterminate, checker.describe(LAYOUT, "com.example.MyView", null, "android:text"))
        // A non-android: (app:/custom) attribute on a framework widget → not the framework metadata's concern.
        assertEquals(AttrInfo.Indeterminate, checker.describe(LAYOUT, "TextView", null, "app:layout_constraintTop_toTopOf"))
    }

    @Test
    fun layoutParamsAreJudgedAgainstTheParentStyleable() {
        // layout_below is valid under RelativeLayout…
        assertIs<AttrInfo.Recognized>(checker.describe(LAYOUT, "Button", "RelativeLayout", "android:layout_below"))
        // …but wrong under LinearLayout (its layout params don't include it).
        assertEquals(AttrInfo.NotAllowed, checker.describe(LAYOUT, "Button", "LinearLayout", "android:layout_below"))
        // …and not judged at all when the parent isn't a known widget (can't be sure).
        assertEquals(AttrInfo.Indeterminate, checker.describe(LAYOUT, "Button", null, "android:layout_below"))
        assertEquals(AttrInfo.Indeterminate, checker.describe(LAYOUT, "Button", "com.example.Custom", "android:layout_below"))
    }

    @Test
    fun manifestChecksValuesButNeverFlagsUnknownAttributes() {
        // launchMode is a recognized closed enum…
        val launch = checker.describe(MANIFEST, "activity", "application", "android:launchMode")
        assertIs<AttrInfo.Recognized>(launch)
        assertTrue("singleTop" in launch.allowedValues!!)
        // exported is a recognized boolean…
        val exported = checker.describe(MANIFEST, "activity", "application", "android:exported")
        assertIs<AttrInfo.Recognized>(exported)
        assertEquals(setOf("true", "false"), exported.allowedValues)
        // …but an attribute the curated catalog doesn't list is left alone (the catalog isn't exhaustive).
        assertEquals(AttrInfo.Indeterminate, checker.describe(MANIFEST, "activity", "application", "android:someNewAttr"))
    }

    @Test
    fun drivesTheLintRuleEndToEndWithTheRealChecker() {
        val xml = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView android:bogus="x" android:visibility="goen" android:text="hello" android:layout_width="16dp"/>
        </LinearLayout>""".trimIndent()
        val problems = XmlLintRules.attributeProblems(parse(xml), LAYOUT, checker)
        // Exactly the wrong attribute + the wrong enum value; layout_width="16dp" and the free-form text are clean.
        assertEquals(1, problems.filterIsInstance<XmlLintRules.AttributeProblem.Unknown>().size)
        val invalid = problems.filterIsInstance<XmlLintRules.AttributeProblem.InvalidValue>()
        assertEquals(listOf("goen"), invalid.map { it.value })
    }

    private fun parse(xml: String) = XmlIncrementalParser().parseFull(Doc(xml))

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = Fake
        override val version: Long = 1
        override fun length(): Int = text.length
    }

    private object Fake : VirtualFile {
        override val path = "res/layout/a.xml"; override val name = "a.xml"
        override val isDirectory = false; override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
