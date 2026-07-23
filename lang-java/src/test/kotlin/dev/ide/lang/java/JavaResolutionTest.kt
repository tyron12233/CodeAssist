package dev.ide.lang.java

import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.TextRange
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.SymbolKind
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Step-3 vertical slice: resolution/typing/structure/scope through the [JavaSourceAnalyzer] SPI. */
class JavaResolutionTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Greeter.java").writeText(
            """
            package com.foo;
            public class Greeter { public String greet(String who) { return "hi " + who; } }
            """.trimIndent()
        )
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env)
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun writeUse(body: String): VirtualFile {
        val f = File(srcRoot, "com/foo/Use.java")
        f.writeText(
            """
            package com.foo;
            class Use {
                void run() {
                    $body
                }
            }
            """.trimIndent()
        )
        return fs.fileFor(f.toPath())
    }

    @Test
    fun `resolve maps a cross-file method call to a Symbol`() = runBlocking {
        val vf = writeUse("""String r = new Greeter().greet("world");""")
        val parsed = analyzer.parsedFile(vf)
        val text = vf.readText().toString()
        val node = parsed.nodeAt(text.indexOf("greet"))
        val res = analyzer.resolve(node)
        assertTrue(res is ResolveResult.Resolved, "greet should resolve; got $res")
        val sym = (res as ResolveResult.Resolved).symbol
        assertEquals("greet", sym.name)
        assertEquals(SymbolKind.METHOD, sym.kind)
        assertEquals("java.lang.String", sym.type?.qualifiedName)
    }

    @Test
    fun `resolveType types a call expression`() = runBlocking {
        val vf = writeUse("""String r = new Greeter().greet("world");""")
        val parsed = analyzer.parsedFile(vf)
        val text = vf.readText().toString()
        val callNode = parsed.nodesIn(TextRange(0, text.length)).first { it.kind == NodeKind.METHOD_CALL }
        assertEquals("java.lang.String", analyzer.resolveType(callNode)?.qualifiedName)
    }

    @Test
    fun `fileStructure lists the type and its members`() = runBlocking {
        val vf = writeUse("int x = 1;")
        val items = analyzer.fileStructure(vf, vf.readText())
        assertTrue(items.any { it.kind == SymbolKind.CLASS && it.name == "Use" }, "class Use")
        assertTrue(items.any { it.kind == SymbolKind.METHOD && it.name == "run" }, "method run")
    }

    @Test
    fun `scopeAt sees enclosing members and in-scope locals`() = runBlocking {
        val vf = writeUse("int local = 1;\n        int y = local;")
        val text = vf.readText().toString()
        val scope = analyzer.scopeAt(vf, text.lastIndexOf("local"))
        val names = scope.symbols().map { it.name }
        assertTrue("local" in names, "the earlier local should be in scope; got $names")
        assertTrue("run" in names, "the enclosing method should be a member; got $names")
    }
}
