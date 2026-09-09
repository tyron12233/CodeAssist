package dev.ide.lang.xml.lint

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.FixContext
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.xml.XmlIncrementalParser
import dev.ide.model.Module
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The unresolved-resource diagnostic and its "Create @type/name" quick-fix (wired through [XmlResourceHost]). */
class XmlDiagnosticProviderTest {

    @Test
    fun unresolvedValueResourceOffersACreateFixThatAppends() = runTest {
        val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:text="@string/missing"/>"""
        val host = FakeHost(
            refs = listOf(XmlResourceRef("string", "missing", xml.indexOf("@string"), xml.indexOf("\"/>"))),
            valueTypes = setOf("string"),
            present = emptySet(), // "missing" is not defined → flagged
        )
        val provider = XmlDiagnosticProvider(host)

        val diags = provider.diagnose(target(xml))
        val unresolved = diags.singleOrNull { it.code == "android.unresolvedResource" }
        assertNotNull(unresolved, "the unresolved reference should be flagged: ${diags.map { it.code }}")

        val fix = unresolved.fixes.singleOrNull()
        assertNotNull(fix, "a value-type unresolved resource carries a create fix")
        assertEquals("Create @string/missing", fix.title)

        fix.computeEdits(FixCtx(target(xml)))
        assertEquals(listOf(Triple("string", "missing", "")), host.appended, "the fix appends the missing value resource")
    }

    @Test
    fun unresolvedFileResourceOffersACreateFileFix() = runTest {
        // A drawable is a file resource — the fix creates res/drawable/missing.xml from a stub.
        val xml = """<ImageView xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/missing"/>"""
        val host = FakeHost(
            refs = listOf(XmlResourceRef("drawable", "missing", xml.indexOf("@drawable"), xml.indexOf("\"/>"))),
            valueTypes = emptySet(),       // drawable is NOT a value type…
            fileTypes = setOf("drawable"), // …it's a file type
            present = emptySet(),
        )
        val unresolved = XmlDiagnosticProvider(host).diagnose(target(xml))
            .single { it.code == "android.unresolvedResource" }
        val fix = unresolved.fixes.singleOrNull()
        assertNotNull(fix, "a file-type unresolved resource carries a create-file fix")
        assertEquals("Create @drawable/missing file", fix.title)
        fix.computeEdits(FixCtx(target(xml)))
        assertEquals(listOf("drawable" to "missing"), host.createdFiles)
    }

    @Test
    fun unresolvedNonCreatableResourceHasNoFix() = runTest {
        // A type that is neither a value nor a file resource (e.g. a style) is flagged but gets no create fix.
        val xml = """<View xmlns:android="http://schemas.android.com/apk/res/android" style="@style/missing"/>"""
        val host = FakeHost(
            refs = listOf(XmlResourceRef("style", "missing", xml.indexOf("@style"), xml.indexOf("\"/>"))),
            valueTypes = emptySet(), fileTypes = emptySet(), present = emptySet(),
        )
        val unresolved = XmlDiagnosticProvider(host).diagnose(target(xml))
            .single { it.code == "android.unresolvedResource" }
        assertTrue(unresolved.fixes.isEmpty(), "no create fix for a non-value, non-file resource type")
    }

    @Test
    fun flagsWrongAttributeWithRemoveFix() = runTest {
        val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:bogus="x"/>"""
        val host = FakeHost(refs = emptyList(), valueTypes = emptySet(), present = emptySet())
        val checker = XmlAttributeChecker { _, _, _, attr ->
            if (attr == "android:bogus") AttrInfo.NotAllowed else AttrInfo.Indeterminate
        }
        val d = XmlDiagnosticProvider(host, checker).diagnose(target(xml))
            .single { it.code == "android.unknownAttribute" }
        assertTrue("android:bogus" in d.message)
        assertEquals("Remove attribute android:bogus", d.fixes.single().title)
    }

    @Test
    fun flagsInvalidAttributeValueWithExpectedHint() = runTest {
        val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:visibility="goen"/>"""
        val host = FakeHost(refs = emptyList(), valueTypes = emptySet(), present = emptySet())
        val checker = XmlAttributeChecker { _, _, _, attr ->
            if (attr == "android:visibility") AttrInfo.Recognized(setOf("visible", "invisible", "gone")) else AttrInfo.Indeterminate
        }
        val d = XmlDiagnosticProvider(host, checker).diagnose(target(xml))
            .single { it.code == "android.invalidAttributeValue" }
        assertTrue("goen" in d.message && "gone" in d.message, "the message names the bad value + the expected set: ${d.message}")
        assertTrue(d.fixes.isEmpty())
    }

    @Test
    fun flagsUnresolvedElementWithADidYouMeanRenameFix() = runTest {
        val xml = "<LinearLayout><TextVeiw/></LinearLayout>"
        val host = FakeHost(refs = emptyList(), valueTypes = emptySet(), present = emptySet())
        val tags = XmlTagChecker { _, tag, _ ->
            if (tag == "TextVeiw") TagInfo.Unresolved(listOf("TextView")) else TagInfo.Recognized(container = true)
        }
        val d = XmlDiagnosticProvider(host, tags = tags).diagnose(target(xml))
            .single { it.code == "android.unknownTag" }
        assertTrue("TextVeiw" in d.message && "TextView" in d.message, d.message)
        val fix = d.fixes.single()
        assertEquals("Change tag to TextView", fix.title)
        val edits = fix.computeEdits(FixCtx(target(xml))).edits.values.single()
        assertEquals(1, edits.size, "a self-closing element has one name to rewrite")
        assertEquals(xml.indexOf("TextVeiw"), edits[0].offset)
        assertEquals("TextView", edits[0].newText.toString())
    }

    @Test
    fun flagsAChildUnderANonContainer() = runTest {
        val host = FakeHost(refs = emptyList(), valueTypes = emptySet(), present = emptySet())
        val tags = XmlTagChecker { _, tag, _ -> TagInfo.Recognized(container = tag == "LinearLayout") }
        val d = XmlDiagnosticProvider(host, tags = tags)
            .diagnose(target("<LinearLayout><TextView><Button/></TextView></LinearLayout>"))
            .single { it.code == "android.illegalChild" }
        assertTrue("TextView" in d.message && "Button" in d.message, d.message)
    }

    @Test
    fun flagsInflaterStructureAndDuplicateIds() = runTest {
        val host = FakeHost(refs = emptyList(), valueTypes = emptySet(), present = emptySet())
        val xml = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <include/>
                <TextView android:id="@+id/x"/>
                <TextView android:id="@+id/x"/>
            </FrameLayout>
        """.trimIndent()
        val codes = XmlDiagnosticProvider(host).diagnose(target(xml)).map { it.code }
        assertTrue("android.includeWithoutLayout" in codes, codes.toString())
        assertEquals(1, codes.count { it == "android.duplicateId" }, codes.toString())
    }

    @Test
    fun valuesFileEntryProblemsAreFlaggedInValuesFilesOnly() = runTest {
        val xml = """
            <resources>
                <string name="a">1</string>
                <string name="a">2</string>
                <color>#fff</color>
            </resources>
        """.trimIndent()
        val host = FakeHost(refs = emptyList(), valueTypes = emptySet(), present = emptySet())
        val provider = XmlDiagnosticProvider(host)
        val inValues = provider.diagnose(target(xml, "/p/res/values/strings.xml")).map { it.code }
        assertTrue("android.duplicateResource" in inValues && "android.resourceMissingName" in inValues, inValues.toString())
        // The same content under res/layout is not a values file, so the entry rules don't apply there.
        val inLayout = provider.diagnose(target(xml)).map { it.code }
        assertTrue(inLayout.none { it == "android.duplicateResource" || it == "android.resourceMissingName" })
    }

    // ---- fakes ----

    /** An analysis target for [xml] as a file at [path] (the provider switches checks by res/ folder). */
    private fun target(xml: String, path: String = "/p/res/layout/a.xml"): AnalysisTarget {
        val file = FileAt(path)
        return FakeTarget(XmlIncrementalParser().parseFull(Doc(xml, file)), file)
    }

    private class FakeHost(
        private val refs: List<XmlResourceRef>,
        private val valueTypes: Set<String>,
        private val present: Set<Pair<String, String>>,
        private val fileTypes: Set<String> = emptySet(),
    ) : XmlResourceHost {
        val appended = mutableListOf<Triple<String, String, String>>()
        val createdFiles = mutableListOf<Pair<String, String>>()
        override fun isViewLike(tag: String) = false
        override fun scanResourceReferences(text: String) = refs
        override fun typeHasAny(file: VirtualFile, rClass: String) = true
        override fun hasResource(file: VirtualFile, rClass: String, name: String) = (rClass to name) in present
        override fun isValueType(rClass: String) = rClass in valueTypes
        override fun appendValueResource(file: VirtualFile, rClass: String, name: String, value: String): String {
            appended += Triple(rClass, name, value); return name
        }
        override fun isFileType(rClass: String) = rClass in fileTypes
        override fun createResourceFile(file: VirtualFile, rClass: String, name: String): String? {
            createdFiles += rClass to name; return "res/$rClass/$name.xml"
        }
    }

    private class FakeTarget(override val parsed: ParsedFile, override val file: VirtualFile) : AnalysisTarget {
        override val documentVersion = 1L
        override val resolver: SourceAnalyzer get() = error("unused by XmlDiagnosticProvider")
        override val index: IndexService get() = error("unused by XmlDiagnosticProvider")
        override val module: Module get() = error("unused by XmlDiagnosticProvider")
        override fun checkCanceled() {}
    }

    private class FixCtx(override val target: AnalysisTarget) : FixContext {
        override fun checkCanceled() {}
    }

    private class Doc(override val text: CharSequence, override val file: VirtualFile = FileAt("/p/res/layout/a.xml")) :
        DocumentSnapshot {
        override val version = 1L
        override fun length() = text.length
    }

    private class FileAt(override val path: String) : VirtualFile {
        override val name = path.substringAfterLast('/')
        override val isDirectory = false; override val exists = true; override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash() = ContentHash("")
        override fun readBytes() = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
