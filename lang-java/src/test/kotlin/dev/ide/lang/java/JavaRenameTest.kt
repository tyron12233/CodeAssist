package dev.ide.lang.java

import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.rename.JavaRename
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Step-6 verification: rename target resolution + per-file reference collection (the seam ide-core drives). */
class JavaRenameTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        File(srcRoot, "com/foo/Greeter.java").writeText(
            """
            package com.foo;
            public class Greeter {
                public String greet(String who) { return who; }
            }
            """.trimIndent()
        )
        File(srcRoot, "com/foo/Use.java").writeText(
            """
            package com.foo;
            class Use {
                void run() {
                    Greeter g = new Greeter();
                    String r = g.greet("x");
                    int total = 1;
                    int y = total + total;
                }
            }
            """.trimIndent()
        )
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() {
        env.close()
        srcRoot.deleteRecursively()
    }

    private fun parse(rel: String) =
        env.parse(rel, File(srcRoot, rel).readText())

    @Test
    fun renamesATypeAcrossFiles() {
        val greeter = parse("com/foo/Greeter.java")
        val use = parse("com/foo/Use.java")
        // Caret on the class declaration name.
        val decl = greeter.text.indexOf("Greeter", greeter.text.indexOf("class "))
        val target = JavaRename.targetAt(greeter, decl)
        assertNotNull(target)
        assertEquals("Greeter", target.oldName)
        assertTrue(target.isType && !target.fileLocal)

        // In Greeter.java: the class name + constructor is implicit (none declared) -> just the decl.
        val inGreeter = JavaRename.referencesIn(greeter, target)
        assertTrue(inGreeter.isNotEmpty(), "declaration should be found in its own file")
        // In Use.java: `Greeter g = new Greeter()` -> two references.
        val inUse = JavaRename.referencesIn(use, target)
        assertEquals(2, inUse.size, "both `Greeter` occurrences in Use.java should be found; got $inUse")
    }

    @Test
    fun renamesAMethod() {
        val greeter = parse("com/foo/Greeter.java")
        val use = parse("com/foo/Use.java")
        val at = greeter.text.indexOf("greet")
        val target = JavaRename.targetAt(greeter, at)
        assertNotNull(target)
        assertEquals("method", target.kind)
        assertTrue(JavaRename.referencesIn(greeter, target).isNotEmpty(), "method declaration")
        assertEquals(1, JavaRename.referencesIn(use, target).size, "the g.greet(...) call site")
    }

    @Test
    fun renamesAFileLocal() {
        val use = parse("com/foo/Use.java")
        val at = use.text.indexOf("total")
        val target = JavaRename.targetAt(use, at)
        assertNotNull(target)
        assertTrue(target.fileLocal, "a local must be file-local")
        // `int total = 1;` + `total + total` -> three occurrences.
        assertEquals(3, JavaRename.referencesIn(use, target).size, "all `total` occurrences")
    }
}
