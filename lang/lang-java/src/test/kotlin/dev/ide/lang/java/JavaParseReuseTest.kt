package dev.ide.lang.java

import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.testkit.TestDocument
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * An unchanged buffer is parsed once, however many passes ask. Every editor pass and both file-structure views
 * reach the parser for the same settled text, and each full PSI parse runs under the process-wide parse lock.
 */
class JavaParseReuseTest {
    private lateinit var srcRoot: File
    private lateinit var env: JavaEnvironment
    private lateinit var fs: LocalFileSystem

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-reuse").toFile()
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    @Test
    fun theSameTextReturnsTheSameTree() {
        val analyzer = JavaSourceAnalyzer(env)
        val vf = fs.fileFor(File(srcRoot, "A.java").toPath())
        val text = "class A { int x; }"
        val first = analyzer.incrementalParser.parseFull(TestDocument(text, vf, 1))
        val again = analyzer.incrementalParser.parseFull(TestDocument(StringBuilder(text), vf, 2))
        assertSame(first, again)

        val edited = analyzer.incrementalParser.parseFull(TestDocument("class A { int y; }", vf, 3))
        assertNotSame(first, edited)
    }

    @Test
    fun fileStructureReusesTheEditorParseAndItsOwnAnswer() {
        val analyzer = JavaSourceAnalyzer(env)
        val vf = fs.fileFor(File(srcRoot, "B.java").toPath())
        val text = "class B { void m() {} }"
        analyzer.incrementalParser.parseFull(TestDocument(text, vf, 1))
        val items = analyzer.fileStructure(vf, text)
        assertEquals(listOf("B", "m"), items.map { it.name })
        assertSame(items, analyzer.fileStructure(vf, StringBuilder(text)))
    }

    @Test
    fun aDeferredAnalyzerReportsItsScopeAndDisposesWithoutAnEnvironment() {
        val analyzer = JavaSourceAnalyzer.deferred(emptyList(), listOf(srcRoot), null)
        assertEquals(listOf(srcRoot.toPath()), analyzer.sourceRootPaths)
        assertEquals(emptyList(), analyzer.classpathJarPaths)
        analyzer.dispose()
    }
}
