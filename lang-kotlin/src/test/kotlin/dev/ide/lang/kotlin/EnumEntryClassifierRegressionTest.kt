package dev.ide.lang.kotlin

import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.lang.kotlin.index.KotlinClassNamesIndex
import dev.ide.lang.kotlin.index.KotlinSourceCallableIndex
import dev.ide.lang.kotlin.index.KotlinSourceSymbolsIndex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enum-entry resolution regressions, exercised over a REAL wired source index (the bare-analyzer harness in
 * [ClassifierAsValueReproTest] has no index and so never populated the offending entries):
 *  1. A qualified enum constant (`Direction.LEFT`) must NOT be flagged "Classifier 'LEFT' does not have a
 *     companion object, and thus must be initialized here" — [KotlinClassNamesIndex] used to descend into
 *     `KtEnumEntry` (which extends `KtClass`) and index `Direction.LEFT` as a SOURCE class, so
 *     `isKnownType("Direction.LEFT")` was true and the use mis-resolved to a nested classifier.
 *  2. A bare enum constant referenced inside the enum's OWN body (`fun opp() = LEFT`, `when (this) { LEFT -> }`)
 *     must resolve — the entries are bare-accessible members of the enclosing enum, not unresolved references.
 */
class EnumEntryClassifierRegressionTest {

    private fun diags(src: String): List<String> {
        val dir = tempProject(mapOf("Main.kt" to src))
        val index = IndexServiceImpl(
            listOf(KotlinClassNamesIndex, KotlinSourceSymbolsIndex, KotlinSourceCallableIndex),
            cacheRoot = Files.createTempDirectory("enum-regress-idx"),
        ).also { runBlocking { it.ensureUpToDate(IndexScope(sourceRoots = listOf(dir))) } }
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir)).also { it.indexService = index }
        val doc = SnippetDoc(src, DiskFile(dir.resolve("Main.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .map { "${it.code}: ${it.message}" }
    }

    @Test fun qualifiedEnumEntryIsNotAClassifierValue() {
        val d = diags(
            "package demo\n" +
                "enum class Direction { LEFT, RIGHT, UP, DOWN }\n" +
                "fun f(): Direction { return Direction.LEFT }\n"
        )
        assertTrue(d.none { it.startsWith("kt.classifierAsValue") },
            "Direction.LEFT is an enum constant, not a classifier value; got $d")
    }

    @Test fun enumEntryAsArgumentIsNotAClassifierValue() {
        val d = diags(
            "package demo\n" +
                "enum class Direction { LEFT, RIGHT, UP, DOWN }\n" +
                "fun g(d: Direction) {}\n" +
                "fun f() { g(Direction.RIGHT) }\n"
        )
        assertTrue(d.none { it.startsWith("kt.classifierAsValue") },
            "an enum constant passed as an argument is a value; got $d")
    }

    @Test fun realTopLevelClassAsValueStillFlagged() {
        // Guard against over-broadening the fix: a genuine class-as-value must still be flagged with the index wired.
        val d = diags(
            "package demo\n" +
                "class Foo(val n: Int)\n" +
                "fun f() { val x = Foo\n  print(x) }\n"
        )
        assertTrue(d.any { it.startsWith("kt.classifierAsValue") && it.contains("Foo") },
            "a real classifier-as-value is still an error; got $d")
    }

    @Test fun bareEntryInsideEnumBodyResolves() {
        val d = diags(
            "package demo\n" +
                "enum class Direction { LEFT, RIGHT;\n" +
                "  fun opp(): Direction = LEFT\n" +
                "}\n"
        )
        assertTrue(d.none { it == "kt.unresolved: Unresolved reference: LEFT" },
            "an enum's own constant is in scope within its body; got $d")
    }

    @Test fun whenOverThisInsideEnumBodyResolves() {
        val d = diags(
            "package demo\n" +
                "enum class Direction { LEFT, RIGHT;\n" +
                "  fun opp(): Direction = when (this) { LEFT -> RIGHT; RIGHT -> LEFT }\n" +
                "}\n"
        )
        assertTrue(d.none { it.startsWith("kt.unresolved:") && (it.endsWith("LEFT") || it.endsWith("RIGHT")) },
            "enum constants are in scope in a when over `this`; got $d")
    }
}
