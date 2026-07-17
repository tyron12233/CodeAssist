package dev.ide.android.support.resources

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stdlib [ResourceModel]: file resources by type folder, value resources from `values*` XML
 * (incl. `<declare-styleable>` child attrs and `<item type=…>`), `@+id/` declarations, config-qualifier
 * collapsing, and merge across a second (dependency) res dir.
 */
class StdlibResourceModelTest {

    @Test
    fun parsesFileValueIdAndStyleableResourcesAcrossDirs() {
        val res = createTempDirectory("res-app")
        write(res, "layout/activity_main.xml", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
              <Button android:id="@+id/submit"/>
              <TextView android:id="@+id/title"/>
            </LinearLayout>
        """)
        write(res, "drawable/ic_logo.png", "")
        write(res, "drawable-hdpi/ic_logo.png", "")           // qualifier dir → same name, one entry
        write(res, "mipmap-anydpi-v26/ic_launcher.xml", "<x/>")
        write(res, "values/values.xml", """
            <resources>
              <string name="app_name">Demo</string>
              <color name="accent">#FF0000</color>
              <dimen name="gap">8dp</dimen>
              <style name="Theme.App"></style>
              <string-array name="planets"><item>Earth</item></string-array>
              <item type="id" name="root"/>
              <declare-styleable name="MyView"><attr name="myColor" format="color"/></declare-styleable>
            </resources>
        """)
        write(res, "values-night/colors.xml", """<resources><color name="bg">#000</color></resources>""")

        val depRes = createTempDirectory("res-lib")
        write(depRes, "values/strings.xml", """<resources><string name="lib_label">L</string></resources>""")

        val repo = StdlibResourceModel.parse(listOf(res, depRes))

        assertEquals(setOf("activity_main"), repo.names(ResourceType.LAYOUT))
        assertEquals(setOf("ic_logo"), repo.names(ResourceType.DRAWABLE), "qualifier dirs collapse to one name")
        assertEquals(setOf("ic_launcher"), repo.names(ResourceType.MIPMAP))
        assertEquals(setOf("app_name", "lib_label"), repo.names(ResourceType.STRING), "dependency res must merge in")
        assertEquals(setOf("accent", "bg"), repo.names(ResourceType.COLOR))
        assertEquals(setOf("planets"), repo.names(ResourceType.ARRAY))
        assertTrue(repo.has(ResourceType.STYLE, "Theme_App"), "dots sanitized to underscores")
        assertTrue(repo.has(ResourceType.STYLEABLE, "MyView"))
        assertTrue(repo.has(ResourceType.ATTR, "myColor"), "declare-styleable child attrs are R.attr entries")
        assertTrue(repo.names(ResourceType.ID).containsAll(setOf("submit", "title", "root")), "ids from @+id and <item type=id>: ${repo.names(ResourceType.ID)}")
    }

    @Test
    fun definitionsTrackEachConfigForGoToDefinition() {
        val res = createTempDirectory("res-cfg")
        write(res, "values/strings.xml", """<resources><string name="hi">Hi</string></resources>""")
        write(res, "values-fr/strings.xml", """<resources><string name="hi">Salut</string></resources>""")
        val repo = StdlibResourceModel.parse(listOf(res))
        assertEquals(setOf("hi"), repo.names(ResourceType.STRING))
        assertEquals(2, repo.definitions(ResourceType.STRING, "hi").size, "one definition per config")
        assertFalse(repo.has(ResourceType.STRING, "bye"))
    }

    @Test
    fun emptyWhenNoResourceDirs() {
        assertTrue(StdlibResourceModel.parse(emptyList()).isEmpty())
    }

    @Test
    fun recoversAllResourceNamesFromAMalformedValuesFile() {
        // A malformed values file (here an unescaped `&`) makes SAX abandon the file at the error, losing every
        // declaration after it — which would empty R.string. The lenient fallback recovers ALL declarations
        // regardless of well-formedness, so completion + the synthetic R survive a broken file.
        val res = createTempDirectory("res-broken")
        write(res, "values/strings.xml", """
            <resources>
              <string name="first">A & B</string>
              <string name="second">C</string>
              <color name="accent">#FF0000</color>
            </resources>
        """)
        val repo = StdlibResourceModel.parse(listOf(res))
        assertEquals(setOf("first", "second"), repo.names(ResourceType.STRING),
            "a broken values file must still yield ALL its string names, not just those before the error")
        assertTrue(repo.has(ResourceType.COLOR, "accent"), "resources after the parse error are recovered too")
    }

    @Test
    fun oneBrokenFileDoesNotWipeOtherFilesResources() {
        // A broken strings.xml must not take down R.layout / another values file — the repository stays usable.
        val res = createTempDirectory("res-mixed")
        write(res, "layout/activity_main.xml", """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"/>""")
        write(res, "values/strings.xml", """<resources><string name="ok">A & B</string></resources>""")
        write(res, "values/colors.xml", """<resources><color name="brand">#123456</color></resources>""")
        val repo = StdlibResourceModel.parse(listOf(res))
        assertEquals(setOf("activity_main"), repo.names(ResourceType.LAYOUT))
        assertTrue(repo.has(ResourceType.STRING, "ok"), "the broken file's own resource is recovered")
        assertTrue(repo.has(ResourceType.COLOR, "brand"), "a sibling well-formed file is unaffected")
    }

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel)
        Files.createDirectories(f.parent)
        Files.writeString(f, content.trimIndent())
    }
}
