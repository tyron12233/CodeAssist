package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The control-flow (CFG) analyses (Phase 3): precise missing-return (EVERY path must return — catches
 * partial-return bodies the old heuristic missed because it backed off on the first `return`), branch-level
 * unreachable code, and definite-assignment (use of an uninitialized local). Every flag is a DEFINITE
 * control-flow verdict; anything the analysis can't decide degrades to no-flag, so there are no false positives.
 */
class KotlinControlFlowTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun b(fn: String) = "package demo\n$fn\n"

    // --- precise missing-return ---

    @Test
    fun partialReturnIsFlagged() {
        // The old heuristic backed off because a `return` was present; the CFG sees the no-`else` fall-through.
        val d = diagnose("MR1.kt", b("fun f(c: Boolean): Int { if (c) return 1 }"))
        assertTrue(d.any { it.code == "kt.missingReturn" }, "`if (c) return 1` (no else) can fall through; got $d")
    }

    @Test
    fun bothBranchesReturnIsClean() {
        val d = diagnose("MR2.kt", b("fun f(c: Boolean): Int { if (c) return 1 else return 2 }"))
        assertTrue(d.none { it.code == "kt.missingReturn" }, "both branches return; got $d")
    }

    @Test
    fun returnAfterIfIsClean() {
        val d = diagnose("MR3.kt", b("fun f(c: Boolean): Int {\n  if (c) return 1\n  return 2\n}"))
        assertTrue(d.none { it.code == "kt.missingReturn" }, "a trailing return covers the fall-through; got $d")
    }

    @Test
    fun whenWithElseAllReturnIsClean() {
        val d = diagnose("MR4.kt", b("fun f(n: Int): Int {\n  when (n) {\n    0 -> return 1\n    else -> return 2\n  }\n}"))
        assertTrue(d.none { it.code == "kt.missingReturn" }, "an exhaustive (else) when whose arms all return is clean; got $d")
    }

    @Test
    fun whenWithoutElseDoesNotFalsePositive() {
        // A no-`else` when of unknown exhaustiveness → UNKNOWN liveness → never flagged (no false positive).
        val d = diagnose("MR5.kt", b("fun f(n: Int): Int {\n  when (n) {\n    0 -> return 1\n    1 -> return 2\n  }\n  return 3\n}"))
        assertTrue(d.none { it.code == "kt.missingReturn" }, "a trailing return after a partial when is clean; got $d")
    }

    // --- branch-level unreachable ---

    @Test
    fun codeAfterIfElseBothReturnIsUnreachable() {
        val d = diagnose("UR1.kt", b("fun f(c: Boolean) {\n  if (c) return else return\n  println(\"x\")\n}"))
        assertTrue(d.any { it.code == "kt.unreachable" }, "code after an if/else that both return is unreachable; got $d")
    }

    @Test
    fun conditionalReturnLeavesCodeReachable() {
        val d = diagnose("UR2.kt", b("fun f(c: Boolean) {\n  if (c) return\n  println(\"x\")\n}"))
        assertTrue(d.none { it.code == "kt.unreachable" }, "code after a conditional return is reachable; got $d")
    }

    // --- definite assignment / uninitialized variable ---

    @Test
    fun readOfNeverAssignedLocalIsFlagged() {
        val d = diagnose("UA1.kt", b("fun f() {\n  val x: Int\n  println(x)\n}"))
        assertTrue(d.any { it.code == "kt.uninitializedVariable" && it.message.contains("x") }, "reading an unassigned val; got $d")
    }

    @Test
    fun readBeforeAssignmentIsFlagged() {
        val d = diagnose("UA2.kt", b("fun f() {\n  val x: Int\n  println(x)\n  x = 1\n}"))
        assertTrue(d.any { it.code == "kt.uninitializedVariable" }, "reading before the assignment; got $d")
    }

    @Test
    fun readInBranchMissingAssignmentIsFlagged() {
        val d = diagnose("UA3.kt", b("fun f(c: Boolean) {\n  val x: Int\n  if (c) x = 1\n  println(x)\n}"))
        assertTrue(d.any { it.code == "kt.uninitializedVariable" }, "x not assigned on the else path; got $d")
    }

    @Test
    fun assignedBeforeReadIsClean() {
        val d = diagnose("UA4.kt", b("fun f() {\n  val x: Int\n  x = 1\n  println(x)\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "assigned before use is clean; got $d")
    }

    @Test
    fun assignedOnBothBranchesIsClean() {
        val d = diagnose("UA5.kt", b("fun f(c: Boolean) {\n  val x: Int\n  if (c) x = 1 else x = 2\n  println(x)\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "assigned on both branches is definite; got $d")
    }

    @Test
    fun assignedInLoopIsNotFlagged() {
        // A back-edge could have assigned it → dropped from the analysis (conservative, no false positive).
        val d = diagnose("UA6.kt", b("fun f(c: Boolean) {\n  var x: Int\n  while (c) { x = 1 }\n  println(x)\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "a loop-assigned var is not flagged; got $d")
    }

    @Test
    fun assignedInClosureIsNotFlagged() {
        val d = diagnose("UA7.kt", b("fun f() {\n  var x: Int\n  run { x = 1 }\n  println(x)\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "a closure-assigned var is not flagged; got $d")
    }

    @Test
    fun assignedInTryIsNotDefiniteAfter() {
        // Read INSIDE the try after the assignment is fine; but this checks we don't crash / mis-handle try.
        val d = diagnose("UA8.kt", b("fun f() {\n  val x: Int\n  try { x = 1 } catch (e: Exception) { return }\n  println(x)\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "x assigned in try, catch returns → reachable path has x; got $d")
    }

    @Test
    fun shadowingInnerLocalIsNotConfused() {
        val d = diagnose("UA9.kt", b("fun f() {\n  val x: Int\n  run { val x = 1\n  println(x) }\n  x = 2\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "the inner initialized `x` read must not flag the outer; got $d")
    }

    @Test
    fun initializedLocalIsNotTracked() {
        val d = diagnose("UA10.kt", b("fun f() {\n  val x: Int = 1\n  println(x)\n}"))
        assertTrue(d.none { it.code == "kt.uninitializedVariable" }, "an initialized local is never flagged; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
