package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Extension functions on a type whose **companion object IS-A that type** (Compose's
 * `Modifier.Companion : Modifier`) must complete on a bare `Type.` reference: writing `Modifier.` resolves to
 * the companion instance, so `fun Modifier.padding()`/`background()` are in scope even though the receiver is
 * written as the type name. Verified on both the binary (`@Metadata`) and project-source paths.
 */
class KotlinCompanionExtensionTest {

    private fun names(srcDir: Path, libJars: List<Path>, file: String, code: String): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars))
        return runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.mapNotNull { it.symbol?.name }
    }

    @Test
    fun binaryCompanionTypeOffersInterfaceExtensions() {
        val srcDir = tempProject(emptyMap())
        val items = names(
            srcDir, listOf(fakeModifierJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeModifier\nfun f() { FakeModifier.fake| }",
        )
        assertTrue("fakePadding" in items, "extension `fun FakeModifier.fakePadding` should appear at `FakeModifier.`; got $items")
        assertTrue("fakeBackground" in items, "extension `fun FakeModifier.fakeBackground` should appear; got $items")
    }

    @Test
    fun binaryCompanionMemberAppearsOnTypeReference() {
        // The `Color.Black` case: a member declared IN the companion object, reached via a bare `Type.`.
        val srcDir = tempProject(emptyMap())
        val items = names(
            srcDir, listOf(fakeModifierJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeModifier\nfun f() { FakeModifier.Un| }",
        )
        assertTrue("Unset" in items, "companion-object member `Unset` should appear at `FakeModifier.`; got $items")
    }

    @Test
    fun sourceCompanionTypeOffersInterfaceExtensions() {
        val srcDir = tempProject(
            mapOf(
                "Mod.kt" to "package demo\ninterface Mod {\n  companion object : Mod {\n    val None: Mod get() = this\n  }\n}\nfun Mod.pad(): Mod = this\nfun Mod.bg(): Mod = this",
            ),
        )
        val ext = names(srcDir, listOf(stdlibJarPath()), "Use.kt", "package demo\nfun f() { Mod.p| }")
        assertTrue("pad" in ext, "source extension `Mod.pad` should appear at `Mod.`; got $ext")
        val mem = names(srcDir, listOf(stdlibJarPath()), "Use.kt", "package demo\nfun f() { Mod.N| }")
        assertTrue("None" in mem, "source companion member `None` should appear at `Mod.`; got $mem")
    }

    @Test
    fun typeWithoutCompanionDoesNotLeakExtensions() {
        // A plain type (no companion that IS-A it) must NOT surface instance extensions on its bare `Type.`.
        val srcDir = tempProject(
            mapOf("Plain.kt" to "package demo\nclass Plain\nfun Plain.ext(): Plain = this"),
        )
        val items = names(srcDir, listOf(stdlibJarPath()), "Use.kt", "package demo\nfun f() { Plain.e| }")
        assertTrue("ext" !in items, "no companion → instance extensions must not leak onto `Plain.`; got $items")
    }

    /** Stage the compiled fake-Modifier classes into a Kotlin-looking jar the symbol service will scan. */
    private fun fakeModifierJar(): Path {
        val jar = Files.createTempFile("fake-modifier", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakemodifier.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeModifier.class")
            add("dev/ide/fakecompose/FakeModifier\$Companion.class")
            add("dev/ide/fakecompose/FakeModifierKt.class")
        }
        return jar
    }
}
