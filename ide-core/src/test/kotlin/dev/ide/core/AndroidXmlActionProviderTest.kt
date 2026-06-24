package dev.ide.core

import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.FixContext
import dev.ide.index.IndexService
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
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

/** The android:→app: AppCompat conversion intention (pure over the DOM + the appCompat-available predicate). */
class AndroidXmlActionProviderTest {

    @Test
    fun convertsSrcToSrcCompatAndDeclaresXmlnsApp() = runTest {
        val xml = """<ImageView xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/x"/>"""
        val provider = AndroidXmlActionProvider { true }
        val caret = xml.indexOf("android:src") + 3 // caret on the attribute name
        val fix = provider.actions(target(xml), TextRange(caret, caret)).singleOrNull()
        assertNotNull(fix, "a conversion is offered on android:src")
        assertEquals("Convert 'android:src' to 'app:srcCompat'", fix.title)

        val edits = fix.computeEdits(FixCtx(target(xml))).edits.values.single()
        assertTrue(edits.any { it.newText == "app:srcCompat" }, "renames the attribute")
        assertTrue(edits.any { it.newText.toString().contains("xmlns:app=") }, "declares xmlns:app")
    }

    @Test
    fun doesNotDuplicateAnExistingXmlnsApp() = runTest {
        val xml = """<ImageView xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" android:src="@drawable/x"/>"""
        val caret = xml.indexOf("android:src") + 3
        val edits = AndroidXmlActionProvider { true }.actions(target(xml), TextRange(caret, caret))
            .single().computeEdits(FixCtx(target(xml))).edits.values.single()
        assertTrue(edits.none { it.newText.toString().contains("xmlns:app=") }, "app namespace already present")
        assertTrue(edits.any { it.newText == "app:srcCompat" })
    }

    @Test
    fun gatedOffWhenAppCompatUnavailable() = runTest {
        val xml = """<ImageView xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/x"/>"""
        val caret = xml.indexOf("android:src") + 3
        assertTrue(AndroidXmlActionProvider { false }.actions(target(xml), TextRange(caret, caret)).isEmpty())
    }

    @Test
    fun noActionOnANonMigratableAttribute() = runTest {
        val xml = """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:text="hi"/>"""
        val caret = xml.indexOf("android:text") + 3
        assertTrue(AndroidXmlActionProvider { true }.actions(target(xml), TextRange(caret, caret)).isEmpty())
    }

    private fun target(xml: String): AnalysisTarget = FakeTarget(XmlIncrementalParser().parseFull(Doc(xml)))

    private class FakeTarget(override val parsed: ParsedFile) : AnalysisTarget {
        override val file: VirtualFile = Fake
        override val documentVersion = 1L
        override val resolver: SourceAnalyzer get() = error("unused")
        override val index: IndexService get() = error("unused")
        override val module: Module get() = error("unused") // the predicate is faked, so module is never read
        override fun checkCanceled() {}
    }

    private class FixCtx(override val target: AnalysisTarget) : FixContext {
        override fun checkCanceled() {}
    }

    private class Doc(override val text: CharSequence) : DocumentSnapshot {
        override val file: VirtualFile = Fake
        override val version = 1L
        override fun length() = text.length
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
