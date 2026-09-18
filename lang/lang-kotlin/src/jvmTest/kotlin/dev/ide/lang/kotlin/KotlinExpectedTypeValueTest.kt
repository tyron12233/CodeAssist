package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * At an expected-type value slot whose type is a companion-object idiom (Compose's `Modifier`, whose bare
 * `Modifier` reference IS its companion), the type's own name must be offered — `modifier = Modifier`. This is
 * resolved from the type SHAPE, so it appears even when the class-names index isn't consulted (here there is
 * none) or would cap the type out among a large project's many `M…` names. Uses the binary `FakeModifier`
 * (mirrors `Modifier`: interface + companion IS-A). A type whose companion is NOT an instance of it (`String`)
 * must NOT be offered as a value.
 */
class KotlinExpectedTypeValueTest {

    private fun labels(code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", code) }.items.map { it.insertText }

    @Test
    fun companionIdiomTypeOfferedAtItsExpectedValueSlot() {
        val code = "package demo\nimport dev.ide.fakecompose.FakeModifier\n" +
            "fun box(modifier: FakeModifier) {}\nfun f() { box(modifier = F|) }"
        assertTrue(
            "FakeModifier" in labels(code),
            "the companion-object type must be offered at its expected value slot; got ${labels(code)}",
        )
    }

    @Test
    fun offeredEvenWithAnEmptyPrefix() {
        val code = "package demo\nimport dev.ide.fakecompose.FakeModifier\n" +
            "fun box(modifier: FakeModifier) {}\nfun f() { box(modifier = |) }"
        assertTrue("FakeModifier" in labels(code), "offered at an empty prefix too; got ${labels(code)}")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), fakeModifierJar())))

        /** A jar with the binary `FakeModifier` (+ its companion), so the shape/companion decode path runs
         *  without a class-names index. A `kotlin_module` entry marks it a Kotlin library so the scan reads it. */
        private fun fakeModifierJar(): Path {
            fun bytes(path: String): ByteArray =
                assertNotNull(KotlinExpectedTypeValueTest::class.java.classLoader.getResourceAsStream(path), "missing $path")
                    .use { it.readBytes() }
            val jar = Files.createTempFile("fake-modifier", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun add(name: String, b: ByteArray) { zos.putNextEntry(ZipEntry(name)); zos.write(b); zos.closeEntry() }
                add("META-INF/fakemodifier.kotlin_module", ByteArray(0))
                add("dev/ide/fakecompose/FakeModifier.class", bytes("dev/ide/fakecompose/FakeModifier.class"))
                add("dev/ide/fakecompose/FakeModifier\$Companion.class", bytes("dev/ide/fakecompose/FakeModifier\$Companion.class"))
            }
            return jar
        }
    }
}
