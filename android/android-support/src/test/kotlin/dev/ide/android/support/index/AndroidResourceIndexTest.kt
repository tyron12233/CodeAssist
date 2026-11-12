package dev.ide.android.support.index

import dev.ide.platform.ByteArrayDataReader
import dev.ide.platform.ByteArrayDataWriter

import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.platform.ContentHash
import dev.ide.lang.dom.ParsedFile
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidResourceIndexTest {

    @Test
    fun scansValueResources() {
        val text = """<resources>
            <string name="app_name">My App</string>
            <color name="primary">#FF0000</color>
            <dimen name="margin">8dp</dimen>
        </resources>""".trimIndent()
        val decls = ResourceFileScanner.scan("values", "/p/res/values/strings.xml", text)
        val byName = decls.associateBy { it.name }
        assertEquals("string", byName["app_name"]?.type)
        assertEquals("color", byName["primary"]?.type)
        assertEquals("dimen", byName["margin"]?.type)
        assertTrue(byName.getValue("primary").offset > 0) // a real offset into the file
        // Value resources carry their resolved literal (for the index-backed completion hint).
        assertEquals("My App", byName["app_name"]?.value)
        assertEquals("#FF0000", byName["primary"]?.value)
        assertEquals("8dp", byName["margin"]?.value)
    }

    @Test
    fun recoversDeclarationsFromAMalformedValuesFile() {
        // A malformed values file (unescaped `&`) makes SAX abandon it at the error, losing every later
        // declaration — which would break `@string/…` resolution/completion. The error-tolerant PSI fallback
        // recovers ALL declarations regardless of well-formedness.
        val text = """<resources>
            <string name="first">A & B</string>
            <string name="second">C</string>
            <color name="accent">#FF0000</color>
        </resources>""".trimIndent()
        val decls = ResourceFileScanner.scan("values", "/p/res/values/strings.xml", text)
        val byName = decls.associateBy { it.name }
        assertEquals("string", byName["first"]?.type, "a broken file must still yield decls after the error: $decls")
        assertEquals("string", byName["second"]?.type)
        assertEquals("color", byName["accent"]?.type)
    }

    @Test
    fun fileResourcesAndIdsCarryNoValue() {
        val text = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/root"/>""".trimIndent()
        val decls = ResourceFileScanner.scan("layout", "/p/res/layout/a.xml", text)
        assertTrue(decls.all { it.value == null }, "file resources + @+id declarations have no value hint: $decls")
    }

    @Test
    fun valueRoundTripsThroughTheExternalizer() {
        val v = ResourceDeclValue("string", "app_name", "/p/res/values/strings.xml", 12, "My App")
        val bytes = ByteArrayDataWriter()
        ResourceDeclExternalizer.write(bytes, v)
        val back = ResourceDeclExternalizer.read(ByteArrayDataReader(bytes.toByteArray()))
        assertEquals(v, back)
        // …and a null value round-trips too.
        val noVal = v.copy(value = null)
        val b2 = ByteArrayDataWriter()
        ResourceDeclExternalizer.write(b2, noVal)
        assertEquals(noVal, ResourceDeclExternalizer.read(ByteArrayDataReader(b2.toByteArray())))
    }

    @Test
    fun styleItemsAreNotMistakenForIds() {
        // <item> inside <style> assigns a framework attr — it is NOT an id resource.
        val text = """<resources>
            <style name="AppTheme" parent="Theme.Material">
                <item name="android:colorPrimary">@color/primary</item>
                <item name="colorAccent">#FF0000</item>
            </style>
            <item type="id" name="real_id"/>
        </resources>""".trimIndent()
        val decls = ResourceFileScanner.scan("values", "/p/res/values/styles.xml", text)
        // The style itself is a resource; the explicit <item type="id"> is an id; the <item name=…> entries are not.
        assertTrue(decls.any { it.type == "style" && it.name == "AppTheme" }, decls.toString())
        assertTrue(decls.any { it.type == "id" && it.name == "real_id" })
        assertTrue(decls.none { it.type == "id" && it.name.contains("color") }, "style items must not become ids: $decls")
        assertTrue(decls.none { it.name.contains(":") }, "namespaced names must be skipped: $decls")
    }

    @Test
    fun scansFileResourceAndDeclaredIds() {
        val text = """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/root">
            <Button android:id="@+id/ok_button"/>
        </LinearLayout>""".trimIndent()
        val decls = ResourceFileScanner.scan("layout", "/p/res/layout/activity_main.xml", text)
        // The file itself is a layout resource; the @+id/ declarations are id resources.
        assertTrue(decls.any { it.type == "layout" && it.name == "activity_main" && it.offset == 0 })
        assertTrue(decls.any { it.type == "id" && it.name == "root" })
        assertTrue(decls.any { it.type == "id" && it.name == "ok_button" })
    }

    @Test
    fun indexFiltersAndKeysByName() {
        val input = FakeInput(
            "/p/res/values/strings.xml",
            "<resources><string name=\"hello\">Hi</string></resources>",
        )
        assertTrue(AndroidResourceIndex.inputFilter.accepts(input))
        val indexed = AndroidResourceIndex.index(input)
        assertEquals("string", indexed["string/hello"]?.single()?.type) // keyed by "<type>/<name>"

        // A non-res XML (e.g. under src/) is not accepted.
        assertTrue(!AndroidResourceIndex.inputFilter.accepts(FakeInput(("/p/src/main/foo.xml"), "<x/>")))
    }

    /**
     * A declaration in a library `res/` folder keyed by content names its file by that key and the path under
     * `res/`, never by where the folder sat when it was indexed: the segment is shared between devices, so the
     * builder's absolute path is a path on someone else's machine. The reader resolves it against its own folder.
     */
    @Test
    fun aContentKeyedLibraryDeclarationStoresAPortablePath() {
        val text = "<resources><color name=\"accent\">#FF336699</color></resources>"
        val built = FakeInput("/home/runner/work/res/values/values.xml", text, IndexOrigin.LIBRARY, "res-sha-1f2e", "values/values.xml")
        val decl = AndroidResourceIndex.index(built).getValue("color/accent").single()
        assertEquals("res-sha-1f2e!/values/values.xml", decl.filePath)

        val here = Path.of("/data/caches/aar/material/res")
        val located = decl.located { key -> here.takeIf { key == "res-sha-1f2e" } }
        assertEquals(here.resolve("values/values.xml").toString(), located.filePath)
        assertEquals(decl, decl.located { null }, "a key this workspace has no folder for is left as it is")
    }

    @Test
    fun aLocationKeyedLibraryFolderAndAProjectFileKeepTheirRealPath() {
        val text = "<resources><color name=\"accent\">#FF336699</color></resources>"
        val local = FakeInput("/p/lib/res/values/values.xml", text, IndexOrigin.LIBRARY, "resdir-res-123", "values/values.xml")
        assertEquals("/p/lib/res/values/values.xml", AndroidResourceIndex.index(local).getValue("color/accent").single().filePath)
        val project = FakeInput("/p/res/values/colors.xml", text)
        val decl = AndroidResourceIndex.index(project).getValue("color/accent").single()
        assertEquals("/p/res/values/colors.xml", decl.filePath)
        assertEquals(decl, decl.located { Path.of("/never") })
    }

    private class FakeInput(
        override val sourcePath: String?,
        private val txt: String,
        override val origin: IndexOrigin = IndexOrigin.SOURCE,
        hash: String = "t",
        unit: String? = null,
    ) : IndexInput {
        override val contentHash = ContentHash(hash)
        override val unitName: String? = unit ?: sourcePath?.substringAfterLast('/').orEmpty()
        override fun bytes(): ByteArray = txt.toByteArray()
        override fun text(): String = txt
        override fun dom(): ParsedFile? = null
    }
}
