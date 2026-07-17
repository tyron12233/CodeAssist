package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A bare call to a top-level extension on the enclosing implicit `this` receiver (Compose's
 * `ComponentActivity.setContent` called inside a `ComponentActivity`) must be flagged unresolved until it is
 * imported: Kotlin requires a top-level extension to be imported even when its receiver type IS the implicit
 * `this`. The editor used to treat ANY implicit-receiver member (extension included) as resolved, so no
 * `kt.unresolved` diagnostic — and thus no "Import" quick-fix — ever fired, yet the code didn't compile.
 * `fakePadding` (a top-level `fun FakeModifier.fakePadding`) stands in for `setContent`; `FakeModifier` is the
 * implicit receiver. Mirrors the member-access rule already enforced for `16.dp`.
 */
class KotlinBareExtensionImportTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun unimportedBareExtensionOnImplicitReceiverIsFlagged() {
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.FakeModifier\n" +
                "fun FakeModifier.use() { fakePadding(1) }"
        )
        assertTrue(
            diags.any { it.code == "kt.unresolved" && "fakePadding" in it.message },
            "an un-imported bare extension on the implicit receiver must be flagged unresolved; got $diags",
        )
    }

    @Test
    fun importedBareExtensionOnImplicitReceiverResolves() {
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.FakeModifier\nimport dev.ide.fakecompose.fakePadding\n" +
                "fun FakeModifier.use() { fakePadding(1) }"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "fakePadding" in it.message },
            "once imported the bare extension must resolve; got $diags",
        )
    }

    @Test
    fun nonExtensionMemberOnImplicitReceiverStillResolves() {
        // A genuine member of the receiver (FakeState.value) resolves bare — the fix gates only extensions.
        val diags = diagnose(
            "package demo\nimport dev.ide.fakecompose.FakeState\n" +
                "fun FakeState<Int>.use() { value }"
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "value" in it.message },
            "a non-extension member of the implicit receiver must still resolve bare; got $diags",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Use.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, listOf(fakeExtJar(), stdlibJarPath())))

        /** Stage the compiled fake extension classes into a jar (mirrors KotlinExtensionAutoImportTest). */
        private fun fakeExtJar(): Path {
            val jar = Files.createTempFile("fake-ext", ".jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
                fun add(name: String) {
                    val bytes = KotlinBareExtensionImportTest::class.java.classLoader.getResourceAsStream(name)
                        ?.use { it.readBytes() } ?: error("missing class resource $name")
                    zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("META-INF/fakeext.kotlin_module")); zos.closeEntry()
                add("dev/ide/fakecompose/FakeModifier.class")
                add("dev/ide/fakecompose/FakeModifier\$Companion.class")
                add("dev/ide/fakecompose/FakeModifierKt.class") // top-level extensions' file facade
                add("dev/ide/fakecompose/FakeState.class")
            }
            return jar
        }
    }
}
