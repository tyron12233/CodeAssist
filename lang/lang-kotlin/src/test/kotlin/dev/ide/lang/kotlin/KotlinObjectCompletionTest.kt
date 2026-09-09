package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A Kotlin `object` singleton (`CardDefaults`, `MaterialTheme`) used as a bare receiver denotes the INSTANCE,
 * so member completion off `Obj.` must list the object's own members — NOT be filtered to statics as a type
 * reference would be. Verified on both the binary (`@Metadata`) and project-source paths, plus a member chain
 * through an object property (`FakeDefaults.theme.scheme` / `MaterialTheme.colorScheme...`).
 */
class KotlinObjectCompletionTest {

    private fun names(srcDir: Path, libJars: List<Path>, file: String, code: String): List<String> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars))
        return runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.mapNotNull { it.symbol?.name }
    }

    @Test
    fun binaryObjectOffersInstanceMembers() {
        val srcDir = tempProject(emptyMap())
        val items = names(
            srcDir, listOf(fakeObjectJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeDefaults\nfun f() { FakeDefaults.fake| }",
        )
        assertTrue("fakeColors" in items, "object member `fakeColors` should appear at `FakeDefaults.`; got $items")
    }

    @Test
    fun binaryObjectMemberChain() {
        val srcDir = tempProject(emptyMap())
        val items = names(
            srcDir, listOf(fakeObjectJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeDefaults\nfun f() { FakeDefaults.theme.sc| }",
        )
        assertTrue("scheme" in items, "chained object-property member `scheme` should appear; got $items")
    }

    @Test
    fun sourceObjectOffersInstanceMembers() {
        val srcDir = tempProject(
            mapOf("Defaults.kt" to "package demo\nobject Defaults {\n  fun colors(): Int = 0\n  val tag: String get() = \"\"\n}"),
        )
        val fn = names(srcDir, listOf(stdlibJarPath()), "Use.kt", "package demo\nfun f() { Defaults.co| }")
        assertTrue("colors" in fn, "source object function `colors` should appear at `Defaults.`; got $fn")
        val prop = names(srcDir, listOf(stdlibJarPath()), "Use.kt", "package demo\nfun f() { Defaults.ta| }")
        assertTrue("tag" in prop, "source object property `tag` should appear at `Defaults.`; got $prop")
    }

    /** Stage the compiled fake `object` classes into a Kotlin-looking jar the symbol service will scan. */
    private fun fakeObjectJar(): Path {
        val jar = Files.createTempFile("fake-object", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakeobject.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeModifier.class")
            add("dev/ide/fakecompose/FakeModifier\$Companion.class")
            add("dev/ide/fakecompose/FakeDefaults.class")
            add("dev/ide/fakecompose/FakeTheme.class")
        }
        return jar
    }
}
