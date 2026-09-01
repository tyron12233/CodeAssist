package dev.ide.lang.kotlin

import dev.ide.index.ClassNameIndex
import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.platform.Disposable
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A project type used as a qualified RECEIVER from another package (`Holder.TAG` with no
 * `import demo.Holder`) needs that import to compile, the same as a library type does.
 *
 * The receiver position used to be the one place an unresolved name went unreported: the same name is
 * flagged in type position, as a bare value and as a call, but a receiver was left alone unless positive
 * evidence claimed it, and the evidence covered library and synthetic types only. So a type declared right
 * there in the project was the case most likely to be written and least likely to be caught.
 */
class KotlinUnimportedTypeReceiverTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun unresolved(fileName: String, code: String) =
        diagnose(fileName, code).filter { it.code == "kt.unresolved" }

    @Test
    fun unimportedProjectTypeInReceiverPositionIsFlagged() {
        for ((name, code) in listOf(
            "Holder" to "package demo.ui\nfun f() { Holder.TAG }",                 // object
            "Holder" to "package demo.ui\nfun f() { Holder.shout() }",             // call through it
            "Widget" to "package demo.ui\nfun f() { Widget.make() }",              // companion
            "Mode" to "package demo.ui\nfun f() { Mode.FAST }",                    // enum entry
        )) {
            val d = unresolved("ui/B.kt", code)
            assertTrue(
                d.any { it.message.contains("Unresolved reference: $name") },
                "`$name` used from another package with no import should be flagged; got $d for:\n$code",
            )
        }
    }

    @Test
    fun projectTypeInScopeIsNotFlagged() {
        for ((file, code) in listOf(
            "A.kt" to "package demo\nfun f() { Holder.TAG }",                          // its own package
            "ui/B.kt" to "package demo.ui\nimport demo.Holder\nfun f() { Holder.TAG }", // explicit import
            "ui/B.kt" to "package demo.ui\nimport demo.*\nfun f() { Holder.TAG }",      // star import
            "ui/B.kt" to "package demo.ui\nfun f() { demo.Holder.TAG }",                // fully qualified
            "A.kt" to "package demo\nfun f() { Outer.Inner.VALUE }",                    // nested-object chain
            "ui/B.kt" to "package demo.ui\nfun f() { Caps.Thing.NAME }",                // a Capitalized PACKAGE
            "ui/B.kt" to "package demo.ui\nfun f() { kotlin.io.println(\"x\") }",       // a package qualifier
            "ui/B.kt" to "package demo.ui\nfun f() { val Holder = \"s\"; Holder.length }", // shadowed by a local
        )) {
            val d = unresolved(file, code)
            assertTrue(d.isEmpty(), "an in-scope receiver must not be flagged; got $d for:\n$code")
        }
    }

    @Test
    fun evidenceIsLimitedToImportableTypes() {
        // A `private` top-level is file-scoped: it cannot be imported, so it is not evidence that the name
        // names something reachable, and the receiver keeps its back-off (no diagnostic without a fix).
        assertFalse(service.hasProjectSourceType("Secret"), "a private top-level is not importable evidence")
        assertTrue(unresolved("ui/B.kt", "package demo.ui\nfun f() { Secret.X }").isEmpty())
        // A name nothing in the project declares stays out of scope for this rule.
        assertFalse(service.hasProjectSourceType("Zorp"), "an unknown name is not project-source evidence")
        assertTrue(service.hasProjectSourceType("Holder"), "a public top-level object is evidence")
    }

    @Test
    fun aProjectJavaSourceTypeIsEvidenceThroughTheIndex() {
        // A project JAVA class has no `.class` on disk while editing, so the SOURCE-origin class-name index is
        // the only place it exists. Driven directly: the shared fixture wires no index.
        val withIndex = KotlinSymbolService(
            sourceRoots = emptyList(), classpathJars = emptyList(),
            index = indexWith(ClassNameValue("demo.JHolder", IndexOrigin.SOURCE, "class")),
        )
        assertTrue(withIndex.hasProjectSourceType("JHolder"), "a project Java source class is evidence")
        assertFalse(withIndex.hasProjectSourceType("Other"), "an unrelated name is not")

        val libraryOnly = KotlinSymbolService(
            sourceRoots = emptyList(), classpathJars = emptyList(),
            index = indexWith(ClassNameValue("lib.JHolder", IndexOrigin.LIBRARY, "class")),
        )
        assertFalse(
            libraryOnly.hasProjectSourceType("JHolder"),
            "a LIBRARY hit belongs to hasLibraryType, not to the project-source rule",
        )
    }

    @Test
    fun theMissingImportIsOfferedAsAQuickFix() {
        val code = "package demo.ui\nfun f() { Holder.TAG }"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("ui/B.kt")))
        runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
        val titles = analyzer.importFixesAt(doc.file, code.indexOf("Holder") + 1).map { it.title }
        assertTrue("Import demo.Holder" in titles, "the lightbulb should offer `Import demo.Holder`; got $titles")
    }

    companion object {
        /** An index answering `classNames` for exactly [values], ready. */
        private fun indexWith(vararg values: ClassNameValue) = object : IndexService {
            @Suppress("UNCHECKED_CAST")
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
                if (id in ClassNameIndex.ALL) values.asSequence()
                    .filter { it.fqn.substringAfterLast('.') == key } as Sequence<V>
                else emptySequence()
            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        val srcDir: Path = tempProject(
            mapOf(
                "Holder.kt" to "package demo\nobject Holder { const val TAG = \"t\"\n  fun shout() {} }",
                "Widget.kt" to "package demo\nclass Widget { companion object { fun make(): Widget = Widget() } }",
                "Mode.kt" to "package demo\nenum class Mode { FAST, SLOW }",
                "Outer.kt" to "package demo\nobject Outer { object Inner { const val VALUE = 1 } }",
                "Secret.kt" to "package demo\nprivate object Secret { const val X = 1 }",
                // A CAPITALIZED package (legal, rare): its root must stay a package qualifier, not a receiver.
                "Caps.kt" to "package Caps\nobject Thing { const val NAME = \"n\" }",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val service = KotlinSymbolService(
            sourceRoots = listOf(dev.ide.vfs.local.LocalFileSystem(srcDir).fileFor(srcDir)),
            classpathJars = emptyList(), index = null,
        )
    }
}
