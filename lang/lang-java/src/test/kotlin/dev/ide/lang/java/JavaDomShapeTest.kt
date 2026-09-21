package dev.ide.lang.java

import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.dom.argumentNodes
import dev.ide.lang.dom.calleeName
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import dev.ide.vfs.local.fileFor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The NEUTRAL shape of a Java call, pinned.
 *
 * Nothing used to assert it, and that is how the shape came to differ from Kotlin's without anyone
 * noticing: a call's arguments arrived as direct children beside the callee (PsiExpressionList is not a
 * PsiExpression, so `isRepresented` dropped it), while Kotlin wrapped them in an argument list. A pattern
 * over "an argument of this call" could be written for one language or the other, never both, which made
 * `DomPatterns`' cross-language promise untrue in the one place it is most often reached for.
 *
 * `KotlinParseTest.neutralCallShapeMatchesTheOtherBackends` asserts the same facts from the other side.
 * The two together are the contract; either one alone just describes a backend.
 */
class JavaDomShapeTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private lateinit var analyzer: JavaSourceAnalyzer
    private lateinit var fs: LocalFileSystem

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-dom-shape").toFile()
        File(srcRoot, "com/foo").mkdirs()
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
        analyzer = JavaSourceAnalyzer(env)
        fs = LocalFileSystem(srcRoot.toPath())
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun parse(body: String): ParsedFile = runBlocking {
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
        analyzer.parsedFile(fs.fileFor(f.toPath()) as VirtualFile)
    }

    private fun ParsedFile.all(): List<DomNode> = nodesIn(TextRange(0, range.end)).toList()
    private fun ParsedFile.first(kind: NodeKind): DomNode =
        assertNotNull(all().firstOrNull { it.kind == kind }, "no $kind in the tree: ${all().map { it.kind.id }}")

    @Test
    fun `a call carries its arguments in an ARGUMENT_LIST child`() {
        val parsed = parse("""rgb(255, 128, 0);""")
        val call = parsed.first(NodeKind.METHOD_CALL)

        val lists = call.children.filter { it.kind == NodeKind.ARGUMENT_LIST }
        assertEquals(1, lists.size, "a call has exactly one argument list; got ${call.children.map { it.kind.id }}")
        assertEquals("rgb", call.calleeName())

        // Java gives an individual argument no node of its own, so the values hang off the list directly.
        // That is the half `argumentNodes()` exists to absorb; see NodeKind.ARGUMENT.
        val args = call.argumentNodes()
        assertEquals(listOf("255", "128", "0"), args.map { it.text().toString() })
        assertTrue(args.all { it.kind == NodeKind.LITERAL }, "got ${args.map { it.kind.id }}")
    }

    @Test
    fun `a string literal is STRING_LITERAL and other constants stay LITERAL`() {
        val parsed = parse(
            """
            String s = "hi";
            int n = 7;
            char c = 'x';
            boolean b = true;
            """.trimIndent()
        )
        val literals = parsed.all().filter {
            it.kind == NodeKind.LITERAL || it.kind == NodeKind.STRING_LITERAL
        }
        val byText = literals.associate { it.text().toString() to it.kind }

        assertEquals(NodeKind.STRING_LITERAL, byText["\"hi\""], "a string is its own kind")
        assertEquals(NodeKind.LITERAL, byText["7"], "a number is not a string")
        assertEquals(NodeKind.LITERAL, byText["'x'"], "a char is not a string")
        assertEquals(NodeKind.LITERAL, byText["true"], "a boolean is not a string")
    }

    @Test
    fun `new is a CONSTRUCTOR_CALL with the same argument shape`() {
        val parsed = parse("""Object o = new String("hi");""")
        val ctor = parsed.first(NodeKind.CONSTRUCTOR_CALL)

        assertEquals("String", ctor.calleeName())
        assertEquals(listOf("\"hi\""), ctor.argumentNodes().map { it.text().toString() })
        assertEquals(
            NodeKind.STRING_LITERAL,
            ctor.argumentNodes().single().kind,
            "the argument of a constructor call reads exactly like the argument of a call",
        )
    }

    @Test
    fun `calleeName strips the qualifier so it matches the Kotlin spelling`() {
        // Java puts the whole `a.b.c` on the callee node; Kotlin nests it above the call. Both answer `max`.
        val parsed = parse("""int m = java.lang.Math.max(1, 2);""")
        val call = parsed.first(NodeKind.METHOD_CALL)
        assertEquals("max", call.calleeName())
    }

    @Test
    fun `an empty argument list is still a list with no arguments`() {
        val parsed = parse("""String s = toString();""")
        val call = parsed.first(NodeKind.METHOD_CALL)
        assertTrue(call.children.any { it.kind == NodeKind.ARGUMENT_LIST }, "`f()` still has the `()` node")
        assertEquals(emptyList(), call.argumentNodes())
    }
}
