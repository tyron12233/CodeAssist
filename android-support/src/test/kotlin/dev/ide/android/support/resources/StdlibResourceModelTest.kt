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

    private fun write(root: Path, rel: String, content: String) {
        val f = root.resolve(rel)
        Files.createDirectories(f.parent)
        Files.writeString(f, content.trimIndent())
    }
}
