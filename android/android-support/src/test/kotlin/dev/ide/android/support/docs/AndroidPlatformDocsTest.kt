package dev.ide.android.support.docs

import dev.ide.android.support.docs.AndroidPlatformDocs.ClassDocs
import dev.ide.android.support.docs.AndroidPlatformDocs.MethodDocs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPlatformDocsTest {

    private val classes = listOf(
        ClassDocs(
            "a.b", "Widget", "/**\n * A widget.\n * @since 1\n */",
            listOf(
                MethodDocs("Widget", listOf("context"), null),
                MethodDocs("setText", listOf("text"), "/**\n * Sets the text.\n * @param text the text\n */"),
                MethodDocs("setText", listOf("text", "type"), "/** Sets typed text. */"),
            ),
        ),
        ClassDocs("a.b", "Widget.Listener", null, listOf(MethodDocs("onEvent", listOf("widget", "code"), null))),
        ClassDocs("a.b.c", "Deep", "/** Deep. */", emptyList()),
    )

    @Test
    fun roundTripsAndLooksUpByOwnerNameAndArity() {
        val docs = AndroidPlatformDocs.read(AndroidPlatformDocs.write(36, classes))
        assertEquals(36, docs.platformLevel)
        assertEquals(listOf("text", "type"), docs.method("a.b.Widget", "setText", 2)?.names)
        assertEquals("Sets the text.", docs.method("a.b.Widget", "setText", 1)?.doc, "cleaned: markers and @tags dropped")
        assertTrue(docs.methodRaw("a.b.Widget", "setText", 1)!!.contains("@param text"), "raw keeps the tags")
        assertEquals(listOf("context"), docs.method("a.b.Widget", "Widget", 1)?.names, "constructor under the simple name")
        // Nested types answer under both spellings; a sub-package does not shadow its parent's classes.
        assertEquals(listOf("widget", "code"), docs.method("a.b.Widget.Listener", "onEvent", 2)?.names)
        assertEquals(listOf("widget", "code"), docs.method("a.b.Widget\$Listener", "onEvent", 2)?.names)
        assertEquals("A widget.", docs.classDoc("a.b.Widget"))
        assertEquals("/** Deep. */", docs.classDocRaw("a.b.c.Deep"))
        assertNull(docs.method("a.b.Widget", "missing", 0))
        assertNull(docs.classDoc("x.y.Nope"))
    }

    @Test
    fun readsFromAFileOneBlockAtATime() {
        val file = Files.createTempFile("android-docs", ".bin")
        Files.write(file, AndroidPlatformDocs.write(36, classes))
        val docs = AndroidPlatformDocs.open(file)
        assertEquals(listOf("text"), docs.method("a.b.Widget", "setText", 1)?.names)
        assertEquals("Deep.", docs.classDoc("a.b.c.Deep"))
    }

    @Test
    fun theBundledPackAnswersRealPlatformApis() {
        val docs = assertNotNull(AndroidPlatformDocs.bundled(Files.createTempDirectory("docs-cache")), "android-docs.bin is bundled")
        assertTrue(docs.platformLevel >= 36)
        assertEquals(listOf("layoutResID"), docs.method("android.app.Activity", "setContentView", 1)?.names)
        assertEquals(listOf("v"), docs.method("android.view.View.OnClickListener", "onClick", 1)?.names)
        assertEquals(listOf("id", "name", "importance"), docs.method("android.app.NotificationChannel", "NotificationChannel", 3)?.names)
        assertEquals(listOf("index", "element"), docs.method("java.util.List", "add", 2)?.names)
        assertTrue(docs.method("android.content.Context", "getSystemService", 1)!!.doc!!.startsWith("Return the handle to a system-level service"))
        assertTrue(docs.classDocRaw("android.app.Activity")!!.startsWith("/**"))
    }
}
