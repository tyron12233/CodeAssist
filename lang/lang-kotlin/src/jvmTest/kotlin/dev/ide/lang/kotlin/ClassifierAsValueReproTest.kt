package dev.ide.lang.kotlin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A CLASSIFIER used as a VALUE (`columns = GridCells.Fixed`, `val x = Foo`) must be flagged — the compiler's
 * "Classifier 'X' does not have a companion object, and thus must be initialized here". Conservative: an object,
 * enum entry, companion-bearing class, constructor call, type position, `::class`, or nested object reference
 * (all legitimate values) must NOT be flagged.
 */
class ClassifierAsValueReproTest {

    private fun hasClassifierError(src: String): Boolean {
        val dir = tempProject(mapOf("Main.kt" to src))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        val doc = SnippetDoc(src, DiskFile(dir.resolve("Main.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
            .any { it.code == "kt.classifierAsValue" }
    }

    @Test fun flagsTopLevelClassAsValue() =
        assertTrue(hasClassifierError("package demo\nclass Foo(val n: Int)\nfun f() { val x = Foo }\n"))

    @Test fun flagsNestedClassAsValue() =
        assertTrue(hasClassifierError("package demo\nclass Outer { class Inner(val n: Int) }\nfun f() { val x = Outer.Inner }\n"))

    @Test fun flagsClassPassedAsArgument() =
        assertTrue(hasClassifierError("package demo\nclass Foo(val n: Int)\nfun g(f: Foo) {}\nfun f() { g(Foo) }\n"))

    @Test fun doesNotFlagObject() =
        assertFalse(hasClassifierError("package demo\nobject Obj\nfun f() { val x = Obj }\n"))

    @Test fun doesNotFlagEnumEntry() =
        assertFalse(hasClassifierError("package demo\nenum class E { A }\nfun f() { val x = E.A }\n"))

    @Test fun doesNotFlagCompanionBearingClass() =
        assertFalse(hasClassifierError("package demo\nclass WithComp { companion object }\nfun f() { val x = WithComp }\n"))

    @Test fun doesNotFlagConstructorCall() =
        assertFalse(hasClassifierError("package demo\nclass Foo(val n: Int)\nfun f() { val x = Foo(1) }\n"))

    @Test fun doesNotFlagTypePosition() =
        assertFalse(hasClassifierError("package demo\nclass Foo(val n: Int)\nfun f() { val x: Foo? = null }\n"))

    @Test fun doesNotFlagClassLiteral() =
        assertFalse(hasClassifierError("package demo\nclass Foo(val n: Int)\nfun f() { val x = Foo::class }\n"))

    @Test fun doesNotFlagNestedObjectReference() =
        assertFalse(hasClassifierError("package demo\nclass Outer { object Inner }\nfun f() { val x = Outer.Inner }\n"))

    @Test fun doesNotFlagStaticMemberAccess() =
        assertFalse(hasClassifierError("package demo\nclass Foo { companion object { const val N = 1 } }\nfun f() { val x = Foo.N }\n"))
}
