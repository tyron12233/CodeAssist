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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `import kotlin.test.Test` in a test source. On the JVM `kotlin-test` holds only the assertions: the annotations
 * are typealiases in a framework artifact (`kotlin-test-junit5`: `Test = org.junit.jupiter.api.Test`), which the
 * Kotlin Gradle plugin adds behind the user's back and the Gradle import now adds too. This is the editor's half:
 * with the framework jar indexed the alias resolves, and without it the import is the "Unresolved reference: Test"
 * users saw. The index is a fake fed by the REAL index producers over the jars (as in
 * [KotlinLibraryTypeAliasImportTest]), so it sees what a classpath scan sees.
 */
class KotlinTestFrameworkResolutionTest {

    private val code = """
        package demo

        import kotlin.test.Test
        import kotlin.test.assertTrue

        class GreetingTest {
            @Test
            fun greets() {
                assertTrue("Hello".startsWith("He"))
            }
        }
    """.trimIndent()

    @Test
    fun theTestAnnotationResolvesThroughTheFrameworkAlias() {
        val diags = unresolved(listOf(jar("kotlin-test-"), jar("kotlin-test-junit5-"), jar("junit-jupiter-api-")))
        assertTrue(diags.none { it.message == UNRESOLVED_TEST }, "kotlin.test.Test must resolve with the framework indexed: ${diags.map { it.message }}")
    }

    @Test
    fun withoutTheFrameworkOnlyTheAnnotationIsUnresolved() {
        val diags = unresolved(listOf(jar("kotlin-test-")))
        assertTrue(diags.any { it.message == UNRESOLVED_TEST }, "without a framework, @Test has nothing to resolve to: ${diags.map { it.message }}")
    }

    // Only the classifier import is under test: this fake index carries the classifier indexes and not the
    // callable ones, so `assertTrue`/`startsWith` read as unresolved in both runs and say nothing here.
    private val UNRESOLVED_TEST = "Unresolved reference: Test"

    /** A jar from this test JVM's classpath: file name [prefix] followed by a version. */
    private fun jar(prefix: String): Path =
        System.getProperty("java.class.path").split(File.pathSeparator).map { Path.of(it) }.first {
            val n = it.fileName.toString()
            n.endsWith(".jar") && n.startsWith(prefix) && n.removePrefix(prefix).firstOrNull()?.isDigit() == true
        }

    private fun unresolved(jars: List<Path>): List<Diagnostic> {
        val srcDir = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(stdlibJarPath()) + jars))
            .apply { indexService = indexOver(jars) }
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("GreetingTest.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == "kt.unresolved" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun indexOver(jars: List<Path>): IndexService {
        val classNames = HashMap<String, MutableList<ClassNameValue>>()
        val typeShapes = HashMap<String, MutableList<TypeShape>>()
        val pkgDecls = HashMap<String, MutableList<PkgDecl>>()
        for (jar in jars) ZipFile(jar.toFile()).use { zip ->
            for (e in zip.entries().asSequence().filter { it.name.endsWith(".class") }) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                if ('$' !in e.name) {
                    val fqn = e.name.removeSuffix(".class").replace('/', '.')
                    classNames.getOrPut(fqn.substringAfterLast('.')) { ArrayList() }.add(ClassNameValue(fqn, IndexOrigin.LIBRARY, "class"))
                }
                KotlinTypeShapeIndex.index(Input(e.name, bytes)).forEach { (k, v) -> typeShapes.getOrPut(k) { ArrayList() }.addAll(v) }
                KotlinPackageDeclIndex.index(Input(e.name, bytes)).forEach { (k, v) -> pkgDecls.getOrPut(k) { ArrayList() }.addAll(v) }
            }
        }
        return object : IndexService {
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
    }

    private class Input(override val unitName: String, private val b: ByteArray) : IndexInput {
        override val origin = IndexOrigin.LIBRARY
        override val contentHash = ContentHash("")
        override val sourcePath: String? = null
        override fun bytes() = b
        override fun text(): String? = null
        override fun dom() = null
    }
}
