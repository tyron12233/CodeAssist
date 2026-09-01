package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinBuiltinsIndex
import dev.ide.lang.kotlin.index.KotlinClassNamesIndex
import dev.ide.lang.kotlin.index.KotlinPackageTypesIndex
import dev.ide.lang.kotlin.index.KotlinTypeShapeIndex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Built-in resolution through the PERSISTENT `kotlin.builtins` index — the real-IDE condition. Once an index
 * is wired it is the SOLE source for built-in shapes (no live `.kotlin_builtins` read), so a decode gap shows
 * up here and nowhere else; the bare-analyzer suites all exercise the live [dev.ide.lang.kotlin.symbols.
 * BuiltinsReader] path instead.
 *
 * Covers the two things that reach a Kotlin built-in only through this index:
 *  - a BUILT-IN ENUM's constants (`AnnotationTarget.CLASS`) — those enums ship only as `.kotlin_builtins`
 *    protobuf, never as a `.class`, so nothing else can supply them;
 *  - `kotlin.Enum` / `kotlin.Any` as the IMPLICIT supertypes of a source declaration (`Direction.LEFT.name`,
 *    `Plain().toString()`), whose members are read from the built-in shape the index holds.
 */
class KotlinBuiltinsIndexResolutionTest {

    private fun unresolved(fileName: String, code: String): List<String> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        analyzer.incrementalParser.parseFull(doc)
        return runBlocking { analyzer.analyze(doc.file).diagnostics }
            .filter { it.code == "kt.unresolved" }.map { it.message }
    }

    @Test
    fun builtinEnumConstantResolvesThroughTheIndex() {
        // Asserted through COMPLETION as well as diagnostics: a receiver that fails to resolve makes the
        // unresolved-member check BACK OFF (it never flags what it cannot type), so a diagnostics-only
        // assertion would pass vacuously if the built-in shape went missing again.
        val offered = runBlocking {
            analyzer.completeAtCaret(srcDir, "C.kt", "package demo\nval t = AnnotationTarget.|\n")
        }.items.mapNotNull { it.symbol?.name }
        assertTrue("CLASS" in offered && "FUNCTION" in offered, "indexed built-in enum offers its entries; got $offered")

        val d = unresolved(
            "T.kt",
            "package demo\nval t = AnnotationTarget.CLASS\nval r = AnnotationRetention.SOURCE\n" +
                "val level = DeprecationLevel.WARNING\n",
        )
        assertTrue(d.isEmpty(), "built-in enum constants resolve off the indexed shape; got $d")
    }

    @Test
    fun implicitSupertypeMembersResolveThroughTheIndex() {
        val offered = runBlocking {
            analyzer.completeAtCaret(srcDir, "C2.kt", "package demo\nenum class Dir { LEFT }\nfun f(d: Dir) { d.| }\n")
        }.items.mapNotNull { it.symbol?.name }
        assertTrue("name" in offered && "ordinal" in offered && "toString" in offered,
            "a source enum offers kotlin.Enum's + kotlin.Any's members off the indexed shapes; got $offered")

        val d = unresolved(
            "E.kt",
            "package demo\nclass Plain\nenum class Direction { LEFT, RIGHT }\n" +
                "fun f(p: Plain, d: Direction) = d.name + d.ordinal + Direction.LEFT.name + p.toString()\n",
        )
        assertTrue(d.isEmpty(), "kotlin.Enum / kotlin.Any members resolve off the indexed shape; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        private val jars = listOf(stdlibJarPath())
        // The kotlin.* binary indexes the IDE registers, so a built-in resolves by NAME here exactly as it
        // does in the product: the class-name/package indexes answer `AnnotationTarget`, and the shape then
        // comes from `kotlin.builtins` (which wins over `kotlin.typeShape` for a `kotlin.*` type).
        private val index = IndexServiceImpl(
            listOf(KotlinClassNamesIndex, KotlinPackageTypesIndex, KotlinTypeShapeIndex, KotlinBuiltinsIndex),
            Files.createTempDirectory("builtins-idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(libraryJars = jars)) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = jars)).also { it.indexService = index }
    }
}
