package dev.ide.lang.kotlin

import dev.ide.lang.highlight.HighlightModifier
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Import lines are classified semantically. The lexical layer can only guess by shape — a Capitalized leaf
 * reads as a type, a callable leaf (`import kotlinx.coroutines.withContext`) was left uncolored — so the
 * imported NAME is now resolved and colored for what it actually names: a type, a member imported through one,
 * or a package-level callable (with its `suspend` / `@Composable` / extension facts).
 */
class KotlinImportHighlightTest {

    private data class Tok(val text: String, val kind: String, val mods: Set<HighlightModifier>)

    private fun tokens(code: String): List<Tok> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Use.kt")))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.semanticHighlighter!!.highlight(doc.file) }
            .map { Tok(code.substring(it.range.start, it.range.end), it.kind.id, it.modifiers) }
    }

    @Test
    fun importedSuspendFunctionIsAFunction() {
        val toks = tokens("package demo\nimport kotlinx.coroutines.withContext\n")
        assertTrue(
            toks.any { it.text == "withContext" && it.kind == "function" && HighlightModifier.SUSPEND in it.mods },
            "an imported suspend function must color as a suspend function; got $toks",
        )
    }

    @Test
    fun importedTypeIsAClass() {
        val toks = tokens("package demo\nimport kotlinx.coroutines.Deferred\n")
        assertTrue(toks.any { it.text == "Deferred" && it.kind == "class" }, "an imported type must color as a class; got $toks")
    }

    @Test
    fun importedExtensionIsMarkedExtension() {
        val toks = tokens("package demo\nimport kotlin.text.trim\n")
        assertTrue(
            toks.any { it.text == "trim" && HighlightModifier.EXTENSION in it.mods },
            "an imported extension must carry the extension modifier; got $toks",
        )
    }

    @Test
    fun importedProjectFunctionAndPropertyAreDistinguished() {
        val toks = tokens("package demo\nimport lib.helper\nimport lib.helperValue\n")
        assertTrue(toks.any { it.text == "helper" && it.kind == "function" }, "a project function import; got $toks")
        assertTrue(toks.any { it.text == "helperValue" && it.kind == "property" }, "a project property import; got $toks")
    }

    @Test
    fun importAliasIsColoredLikeItsTarget() {
        val toks = tokens("package demo\nimport lib.helper as doIt\n")
        assertTrue(toks.any { it.text == "helper" && it.kind == "function" }, "the imported name; got $toks")
        assertTrue(toks.any { it.text == "doIt" && it.kind == "function" }, "the alias must color like its target; got $toks")
    }

    @Test
    fun starImportPackageIsNotColored() {
        // `import pkg.*` — the last segment is a package, so nothing is emitted for it (left to the lexer).
        val toks = tokens("package demo\nimport kotlinx.coroutines.*\n")
        assertTrue(toks.none { it.text == "coroutines" }, "a star import's package must not be classified; got $toks")
    }

    @Test
    fun unresolvableImportIsLeftToTheLexer() {
        val toks = tokens("package demo\nimport com.nope.doesNotExist\n")
        assertTrue(toks.none { it.text == "doesNotExist" }, "an unresolvable import must not be classified; got $toks")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Seed.kt" to "package demo\n",
                "Helpers.kt" to "package lib\nfun helper() {}\nval helperValue: Int = 1\n",
            )
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = listOf(stdlibJarPath(), coroutinesJarPath())))

        /** The kotlinx-coroutines-core jar on the test classpath (carries `kotlinx/coroutines/Deferred.class`). */
        private fun coroutinesJarPath(): Path {
            val cp = System.getProperty("java.class.path").split(File.pathSeparator)
            val entry = cp.firstOrNull { e ->
                e.endsWith(".jar") && runCatching { ZipFile(e).use { it.getEntry("kotlinx/coroutines/Deferred.class") != null } }.getOrDefault(false)
            } ?: error("kotlinx-coroutines-core jar not found on test classpath")
            return Path.of(entry)
        }
    }
}
