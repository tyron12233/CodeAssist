package dev.ide.core

import dev.ide.lang.xml.lint.TagInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The element catalog behind the unknown-element diagnostic, over the REAL bundled SDK metadata (so a
 * framework widget list regression shows up here). SDK-independent: the metadata is the committed
 * `android-sdk-metadata.txt` asset.
 */
class AndroidXmlTagCheckerTest {

    private val layout = "/p/app/src/main/res/layout/activity_main.xml"

    /** A checker whose project-view catalog is [views] (null = not known yet) and whose classpath holds
     *  [classes] (names the view catalog missed but that do exist). */
    private fun checker(views: Map<String, Boolean>? = emptyMap(), classes: Set<String> = emptySet()) =
        AndroidXmlTagChecker(projectViews = { views }, classExists = { _, fqn -> fqn in classes })

    @Test
    fun recognizesFrameworkWidgetsAndTheirContainment() {
        val c = checker()
        assertEquals(TagInfo.Recognized(container = false), c.describe(layout, "TextView", null))
        assertEquals(TagInfo.Recognized(container = true), c.describe(layout, "LinearLayout", null))
        // A framework widget written out in full resolves too.
        assertEquals(TagInfo.Recognized(container = false), c.describe(layout, "android.widget.TextView", null))
    }

    @Test
    fun flagsAMisspelledWidgetAndSuggestsTheRealOne() {
        val info = checker().describe(layout, "TextVeiw", "LinearLayout")
        assertTrue(info is TagInfo.Unresolved, "a non-framework bare tag can't inflate: $info")
        assertEquals("TextView", (info as TagInfo.Unresolved).suggestions.first())
    }

    @Test
    fun flagsAClassThatIsNotOnTheClasspathButNotOneThatIs() {
        val views = mapOf("com.example.ChartView" to false, "androidx.constraintlayout.widget.ConstraintLayout" to true)
        val c = checker(views)
        assertEquals(TagInfo.Recognized(container = false), c.describe(layout, "com.example.ChartView", null))
        assertEquals(
            TagInfo.Recognized(container = true),
            c.describe(layout, "androidx.constraintlayout.widget.ConstraintLayout", null),
        )
        val missing = c.describe(layout, "com.example.Gone", null)
        assertTrue(missing is TagInfo.Unresolved, "a class no catalog knows is unresolved: $missing")
        // A qualified typo is matched on its simple name but suggested in the form a layout must use.
        assertEquals(
            listOf("com.example.ChartView"),
            (c.describe(layout, "com.example.CharView", null) as TagInfo.Unresolved).suggestions,
        )
    }

    @Test
    fun aClassTheViewCatalogMissedButThatExistsIsNotFlagged() {
        // The view catalogs only hold classes whose View ancestry they could walk (a project class extending
        // an AAR view escapes the source scan), so plain existence has the last word.
        val c = checker(views = emptyMap(), classes = setOf("com.example.MyCard"))
        assertEquals(TagInfo.Recognized(container = null), c.describe(layout, "com.example.MyCard", null))
    }

    @Test
    fun staysSilentWhileTheProjectClassesAreUnknown() {
        // Index cold → null catalog → nothing is flagged (a real custom view must never be reported missing).
        val c = checker(views = null)
        assertEquals(TagInfo.Indeterminate, c.describe(layout, "com.example.ChartView", null))
        assertEquals(TagInfo.Indeterminate, c.describe(layout, "TextVeiw", null))
        // …but a framework widget is still recognized, so the containment check keeps working.
        assertEquals(TagInfo.Recognized(container = true), c.describe(layout, "FrameLayout", null))
    }

    @Test
    fun onlyLayoutFilesNameClasses() {
        val c = checker()
        // res/xml preference screens, menus, values and the manifest all use non-class element names.
        assertEquals(TagInfo.Indeterminate, c.describe("/p/res/xml/prefs.xml", "PreferenceScreen", null))
        assertEquals(TagInfo.Indeterminate, c.describe("/p/res/menu/main.xml", "item", "menu"))
        assertEquals(TagInfo.Indeterminate, c.describe("/p/res/values/strings.xml", "string", "resources"))
        assertEquals(TagInfo.Indeterminate, c.describe("/p/src/main/AndroidManifest.xml", "activity", "application"))
    }

    @Test
    fun inflaterPseudoElementsAreRecognizedWithoutAClass() {
        val c = checker()
        assertEquals(TagInfo.Recognized(container = true), c.describe(layout, "merge", null))
        assertEquals(TagInfo.Recognized(container = true), c.describe(layout, "layout", null))
        assertEquals(TagInfo.Recognized(container = false), c.describe(layout, "requestFocus", "TextView"))
        // <include>/<fragment>/<view> exist, but their containment is deliberately unknown.
        for (tag in listOf("include", "fragment", "view")) {
            assertEquals(TagInfo.Recognized(container = null), c.describe(layout, tag, null), tag)
        }
    }
}
