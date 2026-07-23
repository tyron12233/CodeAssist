package dev.ide.core

import dev.ide.lang.completion.TextEdit
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real-view attribute editor's engine side: computing text edits that add / replace / remove an attribute
 * (and auto-declare its `xmlns`), plus the element model + value completion driven by the real bundled SDK
 * metadata. SDK-independent — the metadata is the committed `android-sdk-metadata.txt` asset.
 */
class LayoutAttributeEditTest {

    private fun applyEdits(text: String, edits: List<TextEdit>): String {
        var s = text
        for (e in edits.sortedByDescending { it.range.start }) {
            s = s.substring(0, e.range.start) + e.newText + s.substring(e.range.end)
        }
        return s
    }

    @Test
    fun addReplaceRemoveAndNamespaceAutoDeclare() {
        val dir = Files.createTempDirectory("ide-attr-edit")
        IdeServices.bootstrapDemo(dir).use { ide ->
            // setLayoutAttributeEdits parses the passed text, so an arbitrary path is fine here.
            val file = Paths.get("res/layout/scratch.xml")
            val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="wrap_content"/>"""

            val added = applyEdits(xml, ide.setLayoutAttributeEdits(file, xml, xml.indexOf("<TextView"), null, "android:text", "Hi"))
            assertTrue("""android:text="Hi"""" in added, added)

            val replaced = applyEdits(added, ide.setLayoutAttributeEdits(file, added, added.indexOf("<TextView"), null, "android:text", "Bye"))
            assertTrue(""""Bye"""" in replaced && """"Hi"""" !in replaced, replaced)

            val removed = applyEdits(replaced, ide.removeLayoutAttributeEdits(file, replaced, replaced.indexOf("<TextView"), null, "android:text"))
            assertTrue("android:text" !in removed, removed)

            // Adding an app: attribute auto-declares xmlns:app on the root.
            val noApp = """<TextView xmlns:android="http://schemas.android.com/apk/res/android"/>"""
            val withApp = applyEdits(noApp, ide.setLayoutAttributeEdits(file, noApp, noApp.indexOf("<TextView"), null, "app:foo", "bar"))
            assertTrue("""xmlns:app="http://schemas.android.com/apk/res-auto"""" in withApp, withApp)
            assertTrue("""app:foo="bar"""" in withApp, withApp)
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun escapesAttributeValue() {
        val dir = Files.createTempDirectory("ide-attr-escape")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val file = Paths.get("res/layout/scratch.xml")
            val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android"/>"""
            val out = applyEdits(xml, ide.setLayoutAttributeEdits(file, xml, xml.indexOf("<TextView"), null, "android:text", """a & "b" < c"""))
            assertTrue("&amp;" in out && "&quot;" in out && "&lt;" in out, out)
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun elementModelAndValueCompletionFromRealMetadata() {
        val dir = Files.createTempDirectory("ide-attr-model")
        IdeServices.bootstrapDemo(dir).use { ide ->
            val layout = ide.workspaceRoot.resolve("app/src/main/res/layout/activity_main.xml")
            val text = layout.readText()
            val off = text.indexOf("<TextView")
            assertTrue(off >= 0, "demo layout must contain a TextView")

            val model = ide.layoutElement(layout, text, off, null)
            assertNotNull(model)
            assertEquals("TextView", model.tag)
            val setNames = model.setAttributes.map { it.name }.toSet()
            assertTrue("android:text" in setNames, "set attributes: $setNames")

            // The add list is only valid attributes for a TextView — real ones present, made-up ones absent.
            val addable = model.addable.map { it.name }.toSet()
            assertTrue(addable.any { it == "android:hint" || it == "android:gravity" }, "addable: $addable")
            assertTrue("android:fake_attr_xyz" !in addable)

            // Value completion for layout_width offers the size keywords, exactly like the XML editor.
            val vals = ide.completeLayoutAttributeValue(layout, text, off, null, "android:layout_width", "", 0)
                .items.map { it.label }
            assertTrue("wrap_content" in vals && "match_parent" in vals, "values: $vals")
        }
        dir.toFile().deleteRecursively()
    }
}
