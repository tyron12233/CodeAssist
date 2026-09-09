package dev.ide.lang.kotlin

import dev.ide.lang.completion.CompletionItem
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accepting an unimported **top-level** extension (Compose's `Int.dp`, `String.trim`) auto-adds its
 * `import package.name` — the edit rides on the completion item's `additionalEdits`. A **member** extension
 * (one with a dispatch receiver, like `RowScope.weight`) is NOT importable that way, so it carries no import.
 */
class KotlinExtensionAutoImportTest {

    private fun complete(srcDir: Path, libJars: List<Path>, file: String, code: String): List<CompletionItem> {
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars))
        return runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items
    }

    private fun item(items: List<CompletionItem>, name: String): CompletionItem =
        items.firstOrNull { it.symbol?.name == name }
            ?: error("no completion item named `$name`; got ${items.mapNotNull { it.symbol?.name }}")

    @Test
    fun topLevelExtensionAddsImportOnAccept() {
        val srcDir = tempProject(emptyMap())
        // `fakePadding` is a top-level `fun FakeModifier.fakePadding` in package dev.ide.fakecompose; the use
        // file doesn't import it, so accepting must add `import dev.ide.fakecompose.fakePadding`.
        val items = complete(
            srcDir, listOf(fakeExtJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeModifier\nfun f() { FakeModifier.fakeP| }",
        )
        val edits = item(items, "fakePadding").additionalEdits
        assertEquals(1, edits.size, "expected one import edit; got $edits")
        assertTrue(
            edits.single().newText.contains("import dev.ide.fakecompose.fakePadding"),
            "top-level extension should auto-import its FQN; got `${edits.single().newText}`",
        )
    }

    @Test
    fun memberExtensionAddsNoImport() {
        val srcDir = tempProject(emptyMap())
        // `scopedPad` is a member extension of FakeScope (`fun FakeModifier.scopedPad` inside the class): it
        // needs its dispatch receiver in scope, so it is NOT importable by FQN — no import edit.
        val items = complete(
            srcDir, listOf(fakeExtJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeModifier\nfun f() { FakeModifier.scoped| }",
        )
        assertTrue(item(items, "scopedPad").additionalEdits.isEmpty(), "member extension must not auto-import")
    }

    @Test
    fun alreadyImportedExtensionAddsNoImport() {
        val srcDir = tempProject(emptyMap())
        val items = complete(
            srcDir, listOf(fakeExtJar(), stdlibJarPath()), "Use.kt",
            "import dev.ide.fakecompose.FakeModifier\nimport dev.ide.fakecompose.fakePadding\nfun f() { FakeModifier.fakeP| }",
        )
        assertTrue(item(items, "fakePadding").additionalEdits.isEmpty(), "already-imported extension needs no import")
    }

    /** Stage the compiled fake extension classes (top-level facade + member-extension scope) into a jar. */
    private fun fakeExtJar(): Path {
        val jar = Files.createTempFile("fake-ext", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zos ->
            fun add(name: String) {
                val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("missing class resource $name")
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/fakeext.kotlin_module")); zos.closeEntry()
            add("dev/ide/fakecompose/FakeModifier.class")
            add("dev/ide/fakecompose/FakeModifier\$Companion.class")
            add("dev/ide/fakecompose/FakeModifierKt.class") // top-level extensions' file facade
            add("dev/ide/fakecompose/FakeScope.class")       // member extension lives here
        }
        return jar
    }
}
