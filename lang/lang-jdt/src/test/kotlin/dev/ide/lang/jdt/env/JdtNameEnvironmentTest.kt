package dev.ide.lang.jdt.env

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The custom name environment resolves model/in-memory sources and platform binaries — no disk. */
class JdtNameEnvironmentTest {

    private fun compound(vararg parts: String): Array<CharArray> = parts.map { it.toCharArray() }.toTypedArray()

    @Test
    fun resolvesInMemoryOverlayAndJrtBinariesWithoutDisk() {
        val env = JdtNameEnvironment(
            sourceRoots = emptyList(),
            overlay = mapOf("com.example.Foo" to "package com.example; public class Foo { public int bar() { return 0; } }".toCharArray()),
            classpathJars = emptyList(),
            jdkHome = Path.of(System.getProperty("java.home")),
        )
        try {
            // In-memory source (never written to disk) resolves as a source unit.
            val foo = env.findType(compound("com", "example", "Foo"))
            assertNotNull(foo, "overlay type should resolve")
            assertTrue(foo.isCompilationUnit, "overlay type should be a source unit")

            // Platform type resolves as binary from the jrt image (bytecode, not reflection).
            val string = env.findType(compound("java", "lang", "String"))
            assertNotNull(string, "java.lang.String should resolve from the jrt image")
            assertTrue(string.isBinaryType, "platform type should be a binary type")

            // Packages are recognized (so the compiler can resolve qualified names).
            assertTrue(env.isPackage(emptyArray(), "java".toCharArray()))
            assertTrue(env.isPackage(compound("java"), "lang".toCharArray()))
            assertTrue(env.isPackage(emptyArray(), "com".toCharArray()))
            assertTrue(env.isPackage(compound("com"), "example".toCharArray()))

            // Unknown types do not resolve.
            assertNull(env.findType(compound("no", "such", "Type")))
        } finally {
            env.cleanup()
        }
    }
}
