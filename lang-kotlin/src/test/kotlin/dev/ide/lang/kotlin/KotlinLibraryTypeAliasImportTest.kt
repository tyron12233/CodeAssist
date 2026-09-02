package dev.ide.lang.kotlin

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.index.KotlinPackageDeclIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import dev.ide.lang.kotlin.index.PkgDecl
import dev.ide.lang.kotlin.symbols.TypeShape
import dev.ide.platform.ContentHash
import dev.ide.platform.Disposable
import dev.ide.testkit.TestJars
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A library `typealias` imported by name. A top-level typealias has no `.class` of its own (it lives only in
 * its file facade's `@Metadata`), so it is absent from the class-name index, which is built per `.class`
 * entry. The unresolved-IMPORT check asked only that index, so `import androidx.compose.ui.graphics.Shader`
 * (`actual typealias Shader = android.graphics.Shader`) was reported as "Unresolved reference: Shader" on a
 * file that compiles. The per-package `kotlin.pkgDecls` index does enumerate library typealiases as
 * classifiers, and now answers that existence question.
 *
 * Driven against the REAL kotlinx-coroutines-core jar (on the test classpath), which has the same shape:
 * `CancellationException` and `CompletionHandler` are top-level typealiases, `Job` is a real class. The
 * index services are fakes fed by the REAL index producers over that jar, so the class-name index sees
 * exactly what a `.class` scan sees.
 */
class KotlinLibraryTypeAliasImportTest {

    @Test
    fun typeAliasToAPlatformClassResolves() {
        // `public actual typealias CancellationException = java.util.concurrent.CancellationException`
        assertResolves("import kotlinx.coroutines.CancellationException")
    }

    @Test
    fun typeAliasToAFunctionTypeResolves() {
        // `public typealias CompletionHandler = (cause: Throwable?) -> Unit`
        assertResolves("import kotlinx.coroutines.CompletionHandler")
    }

    @Test
    fun realLibraryClassStillResolves() {
        assertResolves("import kotlinx.coroutines.Job")
    }

    @Test
    fun aDeadImportIsStillFlagged() {
        assertFlagged("import kotlinx.coroutines.NotARealDeclaration", "NotARealDeclaration")
    }

    @Test
    fun aTypeAliasIsNotResolvedFromAnotherPackage() {
        // The alias lives in `kotlinx.coroutines`, so importing it from `kotlinx.coroutines.flow` is dead:
        // the check must stay package-precise, not name-only.
        assertFlagged("import kotlinx.coroutines.flow.CancellationException", "CancellationException")
    }

    @Test
    fun aStarImportedTypeAliasResolves() {
        // The bare name goes through type resolution rather than the import check, and the alias has no shape
        // of its own to find, so a star-imported alias needs the same expansion.
        val diags = diagnose(
            "Star.kt",
            "package demo\n\nimport kotlinx.coroutines.*\n\nfun f(d: CloseableCoroutineDispatcher) {\n  d.close()\n}\n",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "a star-imported library typealias must resolve; got $diags",
        )
    }

    @Test
    fun membersComeFromTheAliasedType() {
        // `public actual typealias CloseableCoroutineDispatcher = ExecutorCoroutineDispatcher`: a value of the
        // alias type has that class's members, which is what makes the type usable rather than merely tolerated.
        val labels = runBlocking {
            analyzer.completeAtCaret(
                srcDir,
                "Members.kt",
                "package demo\n\nimport kotlinx.coroutines.CloseableCoroutineDispatcher\n\n" +
                    "fun f(d: CloseableCoroutineDispatcher) {\n  d.|\n}\n",
            )
        }.items.map { it.label }
        assertTrue(labels.any { it.startsWith("close(") }, "ExecutorCoroutineDispatcher.close() must complete; got ${labels.take(20)}")
        assertTrue("executor" in labels, "ExecutorCoroutineDispatcher.executor must complete; got ${labels.take(20)}")
    }

    private fun assertResolves(importLine: String) {
        val diags = diagnose(importLine)
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "$importLine names a real declaration and must not be flagged; got $diags",
        )
    }

    private fun assertFlagged(importLine: String, leaf: String) {
        val diags = diagnose(importLine)
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains(leaf) },
            "$importLine is dead and must be flagged; got $diags",
        )
    }

    private fun diagnose(importLine: String): List<Diagnostic> =
        diagnose("Use.kt", "package demo\n\n$importLine\n")

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    companion object {
        private val coroutinesJar: Path = TestJars.containing("kotlinx/coroutines/Job.class")
        private val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))

        /** Simple name -> library classes, exactly what a `.class`-entry scan of the jar yields. */
        private val classNames: Map<String, List<ClassNameValue>> = buildMap<String, MutableList<ClassNameValue>> {
            forEachClass { entry, _ ->
                if ('$' in entry) return@forEachClass // a nested class is reached through its parent
                val fqn = entry.removeSuffix(".class").replace('/', '.')
                getOrPut(fqn.substringAfterLast('.')) { ArrayList() }
                    .add(ClassNameValue(fqn, IndexOrigin.LIBRARY, "class"))
            }
        }

        /** Type FQN -> shape, from the REAL `kotlin.typeShape` producer over the jar. */
        private val typeShapes: Map<String, List<TypeShape>> = buildMap<String, MutableList<TypeShape>> {
            forEachClass { entry, bytes ->
                KotlinTypeShapeIndex.index(FakeInput(entry, bytes)).forEach { (fqn, shapes) ->
                    getOrPut(fqn) { ArrayList() }.addAll(shapes)
                }
            }
        }

        /** Package FQN -> declarations, from the REAL `kotlin.pkgDecls` producer over the jar. */
        private val pkgDecls: Map<String, List<PkgDecl>> = buildMap<String, MutableList<PkgDecl>> {
            forEachClass { entry, bytes ->
                KotlinPackageDeclIndex.index(FakeInput(entry, bytes)).forEach { (pkg, decls) ->
                    getOrPut(pkg) { ArrayList() }.addAll(decls)
                }
            }
        }

        private fun forEachClass(action: (String, ByteArray) -> Unit) {
            ZipFile(coroutinesJar.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.name.endsWith(".class")) continue
                    action(e.name, zip.getInputStream(e).use { it.readBytes() })
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private val index = object : IndexService {
            override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> = when (id.value) {
                "java.classNames" -> classNames[key].orEmpty().asSequence() as Sequence<V>
                "kotlin.pkgDecls" -> pkgDecls[key].orEmpty().asSequence() as Sequence<V>
                "kotlin.typeShape" -> typeShapes[key].orEmpty().asSequence() as Sequence<V>
                else -> emptySequence()
            }

            override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> = emptySequence()
            override suspend fun ensureUpToDate(scope: IndexScope) {}
            override suspend fun reindexSource(path: Path, text: String) {}
            override val status = IndexStatus(ready = true)
            override fun observeStatus(listener: (IndexStatus) -> Unit) = Disposable { }
        }

        private val analyzer = KotlinSourceAnalyzer(
            fakeContext(srcDir, libJars = listOf(stdlibJarPath(), coroutinesJar))
        ).apply { indexService = index }

        private class FakeInput(override val unitName: String, private val b: ByteArray) : IndexInput {
            override val origin = IndexOrigin.LIBRARY
            override val contentHash = ContentHash("")
            override val sourcePath: Path? = null
            override fun bytes() = b
            override fun text(): String? = null
            override fun dom() = null
        }
    }
}
