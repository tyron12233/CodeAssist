package dev.ide.android.support.resources

import dev.ide.testkit.withTempDir
import dev.ide.testkit.writeSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CachingResourceModel] must build exactly the repository [StdlibResourceModel] does, while re-parsing only the
 * res XML files that changed since its last build (an unsaved buffer edit, a saved file, a new or deleted one).
 */
class CachingResourceModelTest {

    private fun ResourceRepository.snapshot(): List<String> =
        all().map { "${it.type}:${it.name}:${it.qualifier}:${it.value}" }.sorted() +
            names(ResourceType.STYLEABLE).sorted().map { "styleable:$it=${styleableAttrs(it)}" } +
            listOf("formats:${attrFormat("myColor")}")

    @Test
    fun matchesTheUncachedModelAndReparsesOnlyChangedFiles() {
        withTempDir("res-cache") { res ->
            res.writeSource("layout/main.xml", """<LinearLayout><Button android:id="@+id/go"/></LinearLayout>""")
            res.writeSource("values/strings.xml", """<resources><string name="app">A</string></resources>""")
            res.writeSource("values/attrs.xml", """
                <resources><declare-styleable name="MyView"><attr name="myColor" format="color"/></declare-styleable></resources>
            """)
            res.writeSource("values-night/colors.xml", """<resources><color name="bg">#000</color></resources>""")
            withTempDir("res-dep") { dep ->
                dep.writeSource("values/lib.xml", """<resources><string name="lib">L</string><style name="T.Base"/></resources>""")
                val dirs = listOf(res, dep)
                val model = CachingResourceModel()

                val first = model.parse(dirs)
                assertEquals(StdlibResourceModel.parse(dirs).snapshot(), first.snapshot())
                assertEquals(5, model.lastParsedFiles, "a cold build parses every XML file")

                assertEquals(first.snapshot(), model.parse(dirs).snapshot())
                assertEquals(0, model.lastParsedFiles, "nothing changed, nothing re-parsed")

                // An unsaved edit in one open buffer: only that file is parsed again, and its text wins over disk.
                val strings = res.resolve("values/strings.xml")
                val buffer = """<resources><string name="app">A</string><string name="typed">T</string></resources>"""
                val override: (java.nio.file.Path) -> String? = { if (it == strings) buffer else null }
                val edited = model.parse(dirs, override)
                assertEquals(1, model.lastParsedFiles)
                assertTrue(edited.has(ResourceType.STRING, "typed"))
                assertEquals(StdlibResourceModel.parse(dirs, override).snapshot(), edited.snapshot())
                model.parse(dirs, override)
                assertEquals(0, model.lastParsedFiles, "the same buffer text is a hit")

                // A deleted file drops out of the repository (the cache does not resurrect it).
                Files.delete(res.resolve("values-night/colors.xml"))
                val afterDelete = model.parse(dirs)
                assertFalse(afterDelete.has(ResourceType.COLOR, "bg"))
                assertEquals(StdlibResourceModel.parse(dirs).snapshot(), afterDelete.snapshot())
            }
        }
    }
}
