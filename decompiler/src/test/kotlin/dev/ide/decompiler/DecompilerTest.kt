package dev.ide.decompiler

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the decompiler over the REAL kotlin-stdlib jar on the test classpath (CI-safe, no SDK):
 * @Metadata detection, the Kotlin declaration stub, and a full-body Vineflower Java decompile. Plus
 * [LibrarySources] entry mapping over a synthetic source jar.
 */
class DecompilerTest {

    private fun stdlibJar(): Path {
        val cp = System.getProperty("java.class.path").split(File.pathSeparator)
        val hit = cp.firstOrNull { entry ->
            entry.endsWith(".jar") && runCatching {
                ZipFile(entry).use { it.getEntry("kotlin/Pair.class") != null }
            }.getOrDefault(false)
        } ?: error("kotlin-stdlib jar not found on test classpath")
        return File(hit).toPath()
    }

    @Test
    fun `isKotlin true for a stdlib class, false otherwise`() {
        val d = Decompiler(listOf(stdlibJar()))
        assertTrue(d.isKotlin("kotlin.Pair"))
        assertTrue(!d.isKotlin("com.nope.DoesNotExist"))
    }

    @Test
    fun `kotlin stub renders the class header and members`() {
        val d = Decompiler(listOf(stdlibJar()))
        val stub = assertNotNull(d.kotlinStub("kotlin.Pair"), "expected a stub for kotlin.Pair")
        assertTrue("package kotlin" in stub, "should carry the package:\n$stub")
        assertTrue("class Pair" in stub, "should render the class header:\n$stub")
        assertTrue("component1" in stub || "first" in stub, "should list members:\n$stub")
        assertTrue("read-only" in stub, "should carry the read-only banner:\n$stub")
    }

    @Test
    fun `java source decompiles full bodies via Vineflower`() {
        val d = Decompiler(listOf(stdlibJar()))
        val java = assertNotNull(d.javaSource("kotlin.jvm.internal.Intrinsics"), "expected Java for Intrinsics")
        assertTrue("class Intrinsics" in java, "should decompile the class:\n${java.take(400)}")
        // A real body, not a stub — Intrinsics.checkNotNull throws, so the decompile must contain a throw.
        assertTrue("throw" in java, "should contain full method bodies:\n${java.take(800)}")
        assertTrue(java.length > 400, "decompiled output too short (${java.length})")
    }

    @Test
    fun `multifile facade stub merges the part classes' top-level declarations`() {
        val d = Decompiler(listOf(stdlibJar()))
        // CollectionsKt is a multi-file facade: its `@Metadata` carries no members — they live in part classes.
        val stub = assertNotNull(d.kotlinStub("kotlin.collections.CollectionsKt"), "expected a facade stub")
        assertTrue("listOf" in stub, "the facade stub should surface top-level functions like listOf:\n${stub.take(600)}")
    }

    @Test
    fun `multifile facade java decompiles the part classes`() {
        val d = Decompiler(listOf(stdlibJar()))
        val java = assertNotNull(d.javaSource("kotlin.collections.CollectionsKt"), "expected facade Java")
        assertTrue("listOf" in java, "the decompiled facade should contain the part functions:\n${java.take(600)}")
        assertTrue(java.length > 400, "facade Java too short (${java.length})")
    }

    @Test
    fun `library sources map fqn to whole-file text, longest package prefix and nested types`() {
        val dir = Files.createTempDirectory("decomp-src")
        val jar = dir.resolve("sources.jar")
        val fooText = "package com.example\n\nclass Foo { class Bar }\n"
        val listText = "package java.util;\npublic interface List {}\n"
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            zos.putNextEntry(ZipEntry("com/example/Foo.kt")); zos.write(fooText.toByteArray()); zos.closeEntry()
            // JDK src.zip carries a module prefix before the package path.
            zos.putNextEntry(ZipEntry("java.base/java/util/List.java")); zos.write(listText.toByteArray()); zos.closeEntry()
        }
        try {
            val src = LibrarySources(sourceJars = listOf(jar), sourceDirs = emptyList())
            assertEquals("Foo.kt" to fooText, src.read("com.example.Foo"))
            // A nested type resolves to its top-level file.
            assertEquals("Foo.kt" to fooText, src.read("com.example.Foo.Bar"))
            // Module-prefixed JDK source entry.
            assertEquals("List.java" to listText, src.read("java.util.List"))
            assertEquals(null, src.read("com.example.Missing"))
        } finally {
            jar.deleteIfExists(); dir.deleteIfExists()
        }
    }
}
