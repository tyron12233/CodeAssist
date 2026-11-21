package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property initialization and `val` reassignment as K2 judges them, found by running Kotlin's compiler test data
 * (backingField, cfa, controlFlowAnalysis, reassignment) through the checkers. Each test is one rule; the clean
 * cases next to them are the legal forms the same rule must leave alone.
 */
class KotlinInitializationParityTest {

    /** A property with a backing field needs a value; one whose accessors never touch `field` has none to give
     *  (backingField/VarDeferredInitIn*Class, infos/PropertiesWithBackingFields). */
    @Test
    fun aPropertyWithABackingFieldMustBeInitialized() {
        assertCodes("class C { var a: Int; get() = field }", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
        assertCodes("class C { var a: Int; get() = 1 }", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
        assertCodes("abstract class C { val a: Int }", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
        assertCodes("class C { var a: Int\n private set }", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
        clean("class C { var a: Int; set(v) {} get() = 1 }")
        clean("class C { val a: Int get() = 1 }")
        clean("class C { var a: Int; get() = field\n init { a = 1 } }")
        // A `field` that is a setter parameter, a local or another member is not the backing field.
        clean("var y: Int = 1\nvar x: Int\n    get() = y\n    set(field) { y = field }")
        clean("class C { val two: Int\n get() { val field = 2; return field } }")
        clean("class My(val field: Int) { val third: Int\n get() = this.field }")
        // A getter that reads the field leaves the value to the constructor (constructorConsistency/initwithgetter).
        clean("class My { val x: Int\n get() = field + 1\n init { x = 0 } }")
    }

    /** Deferred initialization does not reach a `var` with a custom setter, nor an `open` property of an open
     *  class: the constructor's assignment would go through the setter, or through an override. */
    @Test
    fun aCustomSetterOrAnOpenPropertyCannotBeInitializedLater() {
        assertCodes("class C { var a: Int; set(v) { field = v }\n init { a = 1 } }", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
        assertCodes("open class C { open var a: Int\n init { a = 1 } }", KotlinDiagnosticCodes.MUST_BE_INITIALIZED)
        clean("class C { var a: Int; set\n init { a = 1 } }")
        clean("class C { open var a: Int\n init { a = 1 } }")
    }

    /** A member `val` that already has a value, a `val` constructor property, a top-level `val` and a `val`
     *  extension property cannot be assigned, an initializer block included (cfa/reassignOfNonLocalProperty_*). */
    @Test
    fun aNonLocalValWithAValueCannotBeAssigned() {
        val decls = "val z: String = \"ok\"\nval Some.y: String\n    get() = \"ok\"\n"
        assertCodes(decls + "class Some { val x: String = \"ok\"\n init { x = \"e\" } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes(decls + "class Some { init { z = \"e\" } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes(decls + "class Some { fun f() { y = \"e\" } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes(decls + "class Some\nfun f(s: Some) { s.y = \"e\" }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("class A(val b: String) { init { b = \"c\" } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("class A { val x: Int = 1\n val y: Int = run { x = 2; 3 } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        clean("var z: String = \"ok\"\nclass Some { init { z = \"e\" } }")
    }

    /** A member `val` declared without a value is initialized by its own class's constructor and nowhere else:
     *  not by a member function, an accessor, another instance (`next?.x`) or an object inside it. */
    @Test
    fun aDeferredMemberValIsAssignedOnlyByItsOwnConstructor() {
        assertCodes("class A { val x: Int\n init { x = 1 }\n fun f() { x = 2 } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("class A(val next: A?) { val x: String\n init { next?.x = \"a\"; x = \"b\" } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes(
            "class A { val a: String\n init { val t = object { fun some() { a = \"1\" } }; a = \"2\"; t.some() } }",
            KotlinDiagnosticCodes.VAL_REASSIGN,
        )
        clean("class A { val x: Int\n init { x = 1 } }")
        clean("class A { val x: Int\n constructor() { this.x = 1 } }")
        clean("class A { val x: Int\n val y = run { x = 1; 2 } }")
    }

    /** `x += 1` on a `val` whose type has no `plusAssign` can only mean `x = x + 1` (controlFlowAnalysis/kt1571,
     *  UninitializedOrReassignedVariables). A `val` collection's `+=` is a call, and legal. */
    @Test
    fun aCompoundAssignmentToAValIsAReassignment() {
        assertCodes("fun f() { val x = 1; x += 2 }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("fun f() { for (i in 0..2) { i += 1 } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("class A { val w = 1\n init { w += 1 } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        clean("fun f() { val l = mutableListOf(1); l += 2 }")
        clean("fun f() { var x = 1; x += 2 }")
    }

    /** A local `val` declared without a value may be assigned once on every path: a second assignment, one in a
     *  loop that comes round again, or one in a `catch` after the `try` may have assigned it, is a reassignment
     *  (the reassignment tests, controlFlowAnalysis/assignedInTryWithCatch, doWhileAssignment). */
    @Test
    fun aDeferredLocalValIsAssignedOncePerPath() {
        assertCodes("fun f(k: Int): Int { val i: Int; for (j in 1..k) { i = j }; return 0 }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("fun f(): Int { val i: Int; i = 1; i = 2; return i }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes(
            "fun f() { val a: Int; try { a = 42 } catch (e: Throwable) { a = 41 }; a.hashCode() }",
            KotlinDiagnosticCodes.VAL_REASSIGN,
        )
        assertCodes("fun f() { val s: String; do { s = \"\" } while (s != \"\") }", KotlinDiagnosticCodes.VAL_REASSIGN)
        clean("fun f() { do { val s: String; s = \"\" } while (true) }")
        clean("fun f(c: Boolean): Int { val i: Int; if (c) i = 1 else i = 2; return i }")
        clean("fun f(c: Boolean): Int { val i: Int; while (true) { i = 1; break }; return i }")
        clean("fun f(c: Int): Int { val i: Int; when (c) { 1 -> i = 1; else -> i = 2 }; return i }")
    }

    /** A variable assigned in a loop is not assigned on the loop's first iteration, before the assignment, nor
     *  after a loop that may run zero times; a `break` after the assignment carries it out of the loop
     *  (controlFlowAnalysis/doWhileNotDefined, reassignment/foronly). */
    @Test
    fun aVariableAssignedInALoopIsJudgedPerPath() {
        assertCodes("fun f() { var x: Int; for (i in 0..1) { x.hashCode(); x = 1 } }", KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE)
        assertCodes("fun f(c: Boolean) { var x: Int; while (c) { x = 1 }; x.hashCode() }", KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE)
        assertCodes("fun f(c: Boolean) { do { if (c) continue; val c2 = false } while (c2) }", KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE)
        clean("fun g(): Int = 1\nfun f(): Int { var x: Int; while (true) { x = g(); if (x > 0) break }; return x }")
        clean("fun f(c: Boolean): Int { var x: Int; do { x = 1 } while (c); return x }")
        clean("fun f(c: Boolean) { do { val c2 = c } while (c2) }")
    }

    /** Properties are initialized in the order they are written, init blocks among them: a read before that point
     *  has no value yet (backingField/TopLevelPropertyInitialization, ForwardAccessToBackingField). */
    @Test
    fun aPropertyReadBeforeItsInitializationIsUninitialized() {
        assertCodes("class A { val b: Int = c\n val c = 1 }", KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE)
        assertCodes("class A { val x: Int\n init { x.hashCode(); x = 1 } }", KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE)
        assertCodes("val t: String = z\nval z: String = \"\"", KotlinDiagnosticCodes.UNINITIALIZED_VARIABLE)
        clean("class A(x: Int) { val x = x }")
        clean("class A { val x: Int\n init { x = 1; x.hashCode() } }")
        clean("class A { val x: Int\n init { if (true) { val x = 2; x.hashCode() }; x = 1 } }")
        clean("class A { val c = 1\n val b: Int = c\n val f = { d }\n val d = 2 }")
        clean("class A { val x: Int\n init { if (true) x = 1 else x = 2 } }")
    }

    /** A deferred member `val` given its value twice by the constructor, or changed by `++` outside it, is
     *  reassigned (controlFlowAnalysis/UninitializedOrReassignedVariables, propertiesInitWithOtherInstance). */
    @Test
    fun aDeferredMemberValAssignedTwiceIsReassigned() {
        assertCodes("class A { val i: Int\n init { i = 1 }\n init { i = 2 } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("class A { val x: String\n init { x = \"b\"; this.x = \"c\" } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        assertCodes("class Outer { val a: Int\n init { a = 1 }\n fun foo() { a++ } }", KotlinDiagnosticCodes.VAL_REASSIGN)
        clean("class Outer { var b: Int\n init { b = 1 }\n fun foo() { b++ } }")
    }

    private fun clean(code: String) {
        val errors = errors(code)
        assertTrue(errors.isEmpty(), "expected `$code` clean, got $errors")
    }

    private fun assertCodes(code: String, vararg expected: String) {
        val errors = errors(code)
        assertEquals(expected.toList(), errors.map { it.code }, "for `$code`: $errors")
    }

    private fun errors(code: String): List<Diagnostic> = runBlocking {
        val text = "package demo\n$code\n"
        val src = tempProject(mapOf("Use.kt" to text))
        val a = KotlinSourceAnalyzer(fakeContext(src, listOfNotNull(stdlibJarPath())))
        val doc = SnippetDoc(text, DiskFile(src.resolve("Use.kt")))
        a.incrementalParser.parseFull(doc)
        a.analyze(doc.file).diagnostics.filter { it.severity == Severity.ERROR && it.code != KotlinDiagnosticCodes.SYNTAX }
    }
}
