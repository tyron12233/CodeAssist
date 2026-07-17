package dev.ide.lang.java

import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.vfs.local.LocalFileSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step 1–2 verification: the per-module [JavaEnvironment] resolves across project source files (via the
 * mounted source root) and against the JDK classpath, and the PSI subtree adapts to the neutral DOM.
 */
class JavaEnvironmentTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        // A cross-file dependency: com.foo.Greeter#greet(String): String
        File(srcRoot, "com/foo").apply { mkdirs() }
        File(srcRoot, "com/foo/Greeter.java").writeText(
            """
            package com.foo;
            public class Greeter {
                public String greet(String who) { return "hi " + who; }
            }
            """.trimIndent()
        )
        env = JavaEnvironment.create(
            classpath = emptyList(),
            sourceRoots = listOf(srcRoot),
            jdkHome = File(System.getProperty("java.home")),
        )
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    @Test
    fun `resolves a cross-file source method and its return type`() {
        val src = """
            package com.foo;
            class Use {
                void run() {
                    String r = new Greeter().greet("world");
                }
            }
        """.trimIndent()
        val psi = env.parse("com/foo/Use.java", src)

        val call = PsiTreeUtil.collectElementsOfType(psi, PsiMethodCallExpression::class.java)
            .first { it.methodExpression.referenceName == "greet" }
        val method = call.resolveMethod()
        assertNotNull(method, "greet() should resolve to Greeter.greet across files via the source root")
        assertEquals("greet", method.name)
        assertEquals("com.foo.Greeter", method.containingClass?.qualifiedName)
        assertEquals("java.lang.String", call.type?.canonicalText, "return type should be String")
    }

    @Test
    fun `adapts the PSI subtree to the neutral DOM`() {
        val src = """
            package com.foo;
            class Use {
                void run() {
                    int x = 1;
                }
            }
        """.trimIndent()
        val psi = env.parse("com/foo/Use.java", src)
        val vf = fs.fileFor(File(srcRoot, "com/foo/Use.java").toPath())
        val parsed = JavaParsedFile(psi, vf, documentVersion = 1L)

        // Compilation unit -> children include the class declaration.
        assertEquals(NodeKind.COMPILATION_UNIT, parsed.kind)
        val classNode = parsed.children.firstOrNull { it.kind == NodeKind.CLASS_DECL }
        assertNotNull(classNode, "the class declaration should be a represented child")

        // nodeAt on the `x` local declaration climbs to a represented node covering it.
        val xOffset = src.indexOf("int x")
        val at = parsed.nodeAt(xOffset)
        assertTrue(at.range.start <= xOffset && xOffset <= at.range.end, "nodeAt should contain the offset")
        // The class node contains a method declaration somewhere in its subtree.
        val hasMethod = parsed.nodesIn(dev.ide.lang.dom.TextRange(0, src.length))
            .any { it.kind == NodeKind.METHOD_DECL }
        assertTrue(hasMethod, "the run() method should be a represented node")
    }
}
