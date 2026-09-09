package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Flow-sensitive nullability smart-casting (Phase 1+2): a null guard on a STABLE, immutable value (a `val`
 * local / parameter) narrows it to non-null downstream, so the null-family checks become flow-precise —
 * a `!!`/`?.`/`?:`/`== null` on the already-narrowed value is redundant/senseless, and a dereference inside
 * the guard is safe. The Kotlin smart-cast STABILITY rules are followed strictly: a `var`, a delegated local,
 * or a member property is NOT smart-cast (we back off — never a false positive), even when guarded.
 */
class KotlinSmartCastFlowTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun body(fn: String) = "package demo\n$fn\n"

    // --- smart-cast recognized on a stable val/param ---

    @Test
    fun redundantNotNullAfterIfGuard() {
        val d = diagnose("A.kt", body("fun f(s: String?) { if (s != null) { s!! } }"))
        assertTrue(d.any { it.code == "kt.redundantNotNull" }, "`s!!` inside `if (s != null)` is redundant; got $d")
    }

    @Test
    fun redundantSafeCallAfterIfGuard() {
        val d = diagnose("B.kt", body("fun f(s: String?) { if (s != null) { s?.length } }"))
        assertTrue(d.any { it.code == "kt.redundantSafeCall" }, "`s?.length` inside `if (s != null)` is redundant; got $d")
    }

    @Test
    fun senselessComparisonAfterIfGuard() {
        val d = diagnose("C.kt", body("fun f(s: String?) { if (s != null) { val b = s == null } }"))
        assertTrue(d.any { it.code == "kt.senselessComparison" }, "`s == null` inside `if (s != null)` is always false; got $d")
    }

    @Test
    fun redundantNotNullAfterElvisReturn() {
        val d = diagnose("D.kt", body("fun f(s: String?) {\n  s ?: return\n  s!!\n}"))
        assertTrue(d.any { it.code == "kt.redundantNotNull" }, "`s!!` after `s ?: return` is redundant; got $d")
    }

    @Test
    fun redundantSafeCallAfterEarlyExitGuard() {
        val d = diagnose("E.kt", body("fun f(s: String?) {\n  if (s == null) return\n  s?.length\n}"))
        assertTrue(d.any { it.code == "kt.redundantSafeCall" }, "`s?.length` after `if (s == null) return` is redundant; got $d")
    }

    @Test
    fun uselessElvisAfterGuard() {
        val d = diagnose("F.kt", body("fun f(s: String?) { if (s != null) { val x = s ?: \"d\" } }"))
        assertTrue(d.any { it.code == "kt.uselessElvis" }, "`s ?: \"d\"` inside `if (s != null)` is a useless elvis; got $d")
    }

    @Test
    fun redundantNotNullAfterRequireNotNull() {
        val d = diagnose("G.kt", body("fun f(s: String?) {\n  requireNotNull(s)\n  s!!\n}"))
        assertTrue(d.any { it.code == "kt.redundantNotNull" }, "`s!!` after `requireNotNull(s)` is redundant; got $d")
    }

    @Test
    fun shortCircuitNarrowingIsRecognized() {
        val d = diagnose("H.kt", body("fun f(s: String?) { val b = s != null && s!!.isEmpty() }"))
        assertTrue(d.any { it.code == "kt.redundantNotNull" }, "`s!!` in the `&&` RHS after `s != null` is redundant; got $d")
    }

    // --- unsafe call: guarded is safe, unguarded is flagged ---

    @Test
    fun guardedDereferenceIsNotUnsafe() {
        val d = diagnose("I.kt", body("fun f(s: String?) { if (s != null) { s.length } }"))
        assertTrue(d.none { it.code == "kt.unsafeNullable" }, "a dereference inside `if (s != null)` must not be flagged unsafe; got $d")
    }

    @Test
    fun emptyNullCheckDoesNotSuppressUnsafeCall() {
        // The reported case: an empty `if (a == null) {}` narrows nothing, so `a.length` is genuinely unsafe.
        val vr = diagnose("U1.kt", body("fun f() {\n  var a: String? = null\n  if (a == null) {}\n  a.length\n}"))
        assertTrue(vr.any { it.code == "kt.unsafeNullable" }, "empty null-check must not hide the unsafe call (var); got $vr")
        val vl = diagnose("U2.kt", body("fun f(a: String?) {\n  if (a == null) {}\n  a.length\n}"))
        assertTrue(vl.any { it.code == "kt.unsafeNullable" }, "empty null-check must not hide the unsafe call (param); got $vl")
    }

    @Test
    fun elseBranchEarlyExitIsSafe() {
        val d = diagnose("U3.kt", body("fun f(s: String?) {\n  if (s != null) {} else return\n  s.length\n}"))
        assertTrue(d.none { it.code == "kt.unsafeNullable" }, "an else-branch early exit narrows s to non-null; got $d")
    }

    @Test
    fun whenGuardSuppressesUnsafeCall() {
        // A `when`-based guard isn't modelled by the flow pass, so we conservatively suppress (no false positive).
        val d = diagnose("U4.kt", body("fun f(s: String?) {\n  when (s) { null -> return\n    else -> {} }\n  s.length\n}"))
        assertTrue(d.none { it.code == "kt.unsafeNullable" }, "a when-guard must not false-positive; got $d")
    }

    @Test
    fun reassignToNullAfterGuardIsUnsafe() {
        val d = diagnose("U5.kt", body("fun f() {\n  var s: String? = \"x\"\n  if (s != null) {\n    s = null\n    s.length\n  }\n}"))
        assertTrue(d.any { it.code == "kt.unsafeNullable" }, "reassigning s to null after the guard makes s.length unsafe; got $d")
    }

    // --- STABILITY: back off (no smart cast) on unstable values, even when guarded ---

    @Test
    fun effectivelyImmutableVarIsSmartCast() {
        // An effectively-immutable `var` (not reassigned after the guard, not captured/loop-written) IS smart-cast
        // by the CFG var-nullability pass.
        val d = diagnose("J.kt", body("fun f() {\n  var s: String? = \"x\"\n  if (s != null) { s!! }\n}"))
        assertTrue(d.any { it.code == "kt.redundantNotNull" }, "an effectively-immutable var should smart-cast; got $d")
    }

    @Test
    fun varReassignedAfterGuardIsNotSmartCast() {
        // The guard is invalidated by the reassignment → `s!!` is NOT redundant (the CFG resets s to unknown).
        val d = diagnose("J2.kt", body("fun f(p: String?) {\n  var s: String? = \"x\"\n  if (s != null) { s = p\n    s!! }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "a reassignment after the guard breaks the smart-cast; got $d")
    }

    @Test
    fun varAssignedInLoopIsNotSmartCast() {
        // A var written in a loop is not effectively immutable (a back-edge) → excluded → not smart-cast.
        val d = diagnose("J3.kt", body("fun f(c: Boolean) {\n  var s: String? = \"x\"\n  while (c) { s = null }\n  if (s != null) { s!! }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "a loop-assigned var must not be smart-cast; got $d")
    }

    @Test
    fun varAssignedInClosureIsNotSmartCast() {
        val d = diagnose("J4.kt", body("fun f() {\n  var s: String? = \"x\"\n  run { s = null }\n  if (s != null) { s!! }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "a closure-assigned var must not be smart-cast; got $d")
    }

    @Test
    fun finalValMemberIsSmartCast() {
        // A same-file final `val` member with a backing field IS stable (spec: not open, no custom getter,
        // same module) — so it smart-casts through a bare guard.
        val d = diagnose("K.kt", body("class C {\n  val s: String? = null\n  fun f() { if (s != null) { s!! } }\n}"))
        assertTrue(d.any { it.code == "kt.redundantNotNull" }, "a final val member should smart-cast; got $d")
    }

    @Test
    fun openValMemberIsNotSmartCast() {
        // An `open val` can be overridden with a custom getter → not stable.
        val d = diagnose("K2.kt", body("open class C {\n  open val s: String? = null\n  fun f() { if (s != null) { s!! } }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "an open val member must not be smart-cast; got $d")
    }

    @Test
    fun customGetterMemberIsNotSmartCast() {
        // A custom getter may return a different value each call → not stable.
        val d = diagnose("K3.kt", body("class C {\n  val s: String? get() = null\n  fun f() { if (s != null) { s!! } }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "a custom-getter member must not be smart-cast; got $d")
    }

    @Test
    fun varMemberIsNotSmartCast() {
        val d = diagnose("K4.kt", body("class C {\n  var s: String? = null\n  fun f() { if (s != null) { s!! } }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "a var member must not be smart-cast; got $d")
    }

    @Test
    fun delegatedLocalIsNotSmartCast() {
        val d = diagnose("L.kt", body("fun f() {\n  val s: String? by lazy { null }\n  if (s != null) { s!! }\n}"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "a delegated local must not be smart-cast (stability); got $d")
    }

    // --- no narrowing → no false positive ---

    @Test
    fun unguardedNotNullIsNotRedundant() {
        val d = diagnose("M.kt", body("fun f(s: String?) { s!! }"))
        assertTrue(d.none { it.code == "kt.redundantNotNull" }, "an unguarded `s!!` is legitimate; got $d")
    }

    @Test
    fun theGuardConditionItselfIsNotSenseless() {
        val d = diagnose("N.kt", body("fun f(s: String?) { if (s != null) {} }"))
        assertTrue(d.none { it.code == "kt.senselessComparison" }, "the guard `s != null` itself must not be flagged; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
