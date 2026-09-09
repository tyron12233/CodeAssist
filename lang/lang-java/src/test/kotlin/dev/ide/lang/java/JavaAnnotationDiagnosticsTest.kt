package dev.ide.lang.java

import dev.ide.lang.dom.Severity
import dev.ide.lang.java.env.JavaEnvironment
import dev.ide.lang.java.parse.JavaDiagnosticCodes
import dev.ide.lang.java.parse.JavaParsedFile
import dev.ide.vfs.local.LocalFileSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Unresolved-reference diagnostics inside Java annotations: an unknown annotation type and an unresolved
 * reference in an annotation argument are both flagged, while a resolvable annotation is not. (The recursive
 * resolver already descends into annotation PSI; this locks that in.)
 */
class JavaAnnotationDiagnosticsTest {
    private lateinit var env: JavaEnvironment
    private lateinit var srcRoot: File
    private val fs = LocalFileSystem(Files.createTempDirectory("java-fs"))

    @BeforeTest
    fun setUp() {
        srcRoot = Files.createTempDirectory("java-src").toFile()
        File(srcRoot, "com/foo").mkdirs()
        env = JavaEnvironment.create(emptyList(), listOf(srcRoot), File(System.getProperty("java.home")))
    }

    @AfterTest
    fun tearDown() { env.close(); srcRoot.deleteRecursively() }

    private fun unresolved(src: String): List<String> {
        val f = File(srcRoot, "com/foo/Use.java"); f.writeText(src)
        return JavaParsedFile(env.parse("com/foo/Use.java", src), fs.fileFor(f.toPath()), 1L)
            .diagnostics.filter { it.severity == Severity.ERROR && it.code == JavaDiagnosticCodes.UNRESOLVED }
            .map { it.message }
    }

    @Test
    fun unknownAnnotationTypeIsReported() {
        val d = unresolved("package com.foo;\n@Foo\nclass Use {}")
        assertTrue(d.any { it.contains("Foo") }, "unknown annotation @Foo should be unresolved; got $d")
    }

    @Test
    fun unresolvedAnnotationArgumentIsReported() {
        val d = unresolved("package com.foo;\n@SuppressWarnings(NOPE)\nclass Use {}")
        assertTrue(d.any { it.contains("NOPE") }, "unresolved annotation arg NOPE should be flagged; got $d")
    }

    @Test
    fun resolvableAnnotationHasNoFalsePositive() {
        val d = unresolved("package com.foo;\nclass Use { @Override public String toString() { return \"\"; } }")
        assertTrue(d.isEmpty(), "a resolvable @Override must not be flagged; got $d")
    }
}
