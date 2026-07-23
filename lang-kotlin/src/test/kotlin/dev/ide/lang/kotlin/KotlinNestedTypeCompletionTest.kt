package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Kotlin `@Metadata` type's NESTED classes (`GridCells.Fixed`, `Icons.Filled`) are reached statically
 * through the enclosing type (`Owner.Nested`), like a Java nested type. Metadata keeps them in a separate
 * `nestedClasses` list — NOT among the type's members — so `Owner.` completion never offered them (only the
 * Java-bytecode path surfaced nested types). This verifies they now appear, and that completing one adds NO
 * spurious auto-import (the selector is already qualified by its receiver).
 */
class KotlinNestedTypeCompletionTest {

    private fun items(libJars: List<Path>, file: String, code: String) =
        runBlocking { KotlinSourceAnalyzer(fakeContext(tempProject(emptyMap()), libJars)).completeAtCaret(tempProject(emptyMap()), file, code) }.items

    @Test
    fun nestedClassesOfAKotlinInterfaceCompleteOffTheEnclosingType() {
        val names = items(
            listOf(fakeGridCellsJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeGridCells\nfun f() { val c = FakeGridCells.| }",
        ).mapNotNull { it.symbol?.name }
        assertTrue("Fixed" in names, "the nested class `Fixed` should appear at `FakeGridCells.`; got $names")
        assertTrue("Adaptive" in names, "the nested class `Adaptive` should appear at `FakeGridCells.`; got $names")
    }

    @Test
    fun completingANestedClassAddsNoImport() {
        // `FakeGridCells.Fixed` is reached through its qualifier, so — unlike a bare type reference — accepting
        // it must NOT insert `import …FakeGridCells.Fixed`.
        val fixed = items(
            listOf(fakeGridCellsJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeGridCells\nfun f() { val c = FakeGridCells.Fix| }",
        ).firstOrNull { it.symbol?.name == "Fixed" }
        assertTrue(fixed != null, "the `Fixed` candidate should be present")
        val imports = fixed.additionalEdits.filter { "import" in it.newText }
        assertTrue(imports.isEmpty(), "completing a nested type via `Owner.` must add no import; got ${imports.map { it.newText }}")
    }

    /** Stage the compiled fake nested-class shapes into a Kotlin-looking jar the symbol service will scan. */
    private fun fakeGridCellsJar(): Path {
        val jar = Files.createTempFile("fake-grid", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakegrid.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeGridCells.class")
            add("dev/ide/fakecompose/FakeGridCells\$Fixed.class")
            add("dev/ide/fakecompose/FakeGridCells\$Adaptive.class")
        }
        return jar
    }
}
