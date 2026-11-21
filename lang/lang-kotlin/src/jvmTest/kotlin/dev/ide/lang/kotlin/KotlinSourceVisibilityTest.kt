package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Access to a project-source declaration its modifiers hide, held to the compiler's rules (the
 * `compiler/testData/diagnostics/tests` visibility cases): a private member is visible only inside its own class
 * (an outer class does not see a nested class's privates) and through a receiver of that class; a protected one
 * inside subclasses; a private top-level declaration only in its own file; a constructor by its own modifier.
 * Each case pairs the hidden access with the visible one beside it, which must stay clean.
 */
class KotlinSourceVisibilityTest {

    /** The errors of [use] in a project that also holds [other] as `Other.kt`. */
    private fun errors(use: String, other: String = "package demo\n"): List<Diagnostic> {
        val dir = tempProject(mapOf("Other.kt" to other, "Use.kt" to use))
        val analyzer = KotlinSourceAnalyzer(fakeContext(dir))
        try {
            val doc = SnippetDoc(use, DiskFile(dir.resolve("Use.kt")))
            return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
                .filter { it.severity == dev.ide.lang.dom.Severity.ERROR }
        } finally {
            analyzer.dispose()
            dir.toFile().deleteRecursively()
        }
    }

    private fun List<Diagnostic>.invisible(use: String, name: String): List<String> =
        filter { it.code == KotlinDiagnosticCodes.INVISIBLE_REFERENCE && use.substring(it.range.start, it.range.end) == name }
            .map { it.message }

    @Test
    fun outerClassDoesNotSeeANestedObjectsPrivates() {
        val use = "package demo\nclass Outer {\n  fun f() { Inner.hidden(); Inner.shown() }\n" +
            "  object Inner { private fun hidden() {}\n    fun shown() {} }\n}\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "hidden").isNotEmpty(), "Inner.hidden is private to Inner; got $errs")
        assertTrue(errs.none { use.substring(it.range.start, it.range.end) == "shown" }, "Inner.shown is public; got $errs")
    }

    @Test
    fun companionPrivatesAreVisibleInTheOuterClassOnly() {
        val use = "package demo\nclass Holder {\n  fun f() = make()\n  companion object { private fun make() = 1 }\n}\n" +
            "fun g() = Holder.make()\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "make").size == 1, "only the top-level Holder.make() is hidden; got $errs")
    }

    @Test
    fun privateMemberThroughASubclassReceiverIsHidden() {
        val use = "package demo\nabstract class A {\n  fun foo(b: B, a: A) { b.prv(); a.prv() }\n  private fun prv() {}\n}\n" +
            "abstract class B : A()\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "prv").size == 1, "b.prv() is hidden, a.prv() is not; got $errs")
    }

    @Test
    fun protectedMemberNeedsASubclass() {
        val use = "package demo\nopen class Base { protected fun p() {} }\n" +
            "class Sub : Base() { fun ok() { p(); this.p() } }\n" +
            "fun outside(b: Base) { b.p() }\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "p").size == 1, "only the access outside a subclass is hidden; got $errs")
    }

    @Test
    fun privateAndProtectedNestedClassesFromOutside() {
        val use = "package demo\nopen class Outer {\n  private class Priv\n  protected class Prot\n  class Pub\n}\n" +
            "class Derived : Outer() { fun f() { Prot(); Outer.Prot() } }\n" +
            "fun g() { Outer.Priv(); Outer.Prot(); Outer.Pub() }\n" +
            "fun h(x: Outer.Priv?) {}\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "Priv").size == 2, "Outer.Priv() and the Outer.Priv type are hidden; got $errs")
        assertTrue(errs.invisible(use, "Prot").size == 1, "Outer.Prot() is hidden only outside a subclass; got $errs")
        assertTrue(errs.none { use.substring(it.range.start, it.range.end) == "Pub" }, "Outer.Pub is public; got $errs")
    }

    @Test
    fun privateTopLevelOfAnotherFile() {
        val other = "package demo\nprivate class Hidden { class Pub }\nprivate fun secret() = 1\nprivate object Po\n"
        val use = "package demo\nfun f() {\n  secret()\n  val h: Hidden = Hidden()\n  val p = Po\n}\nfun g(x: Hidden.Pub) {}\n"
        val errs = errors(use, other)
        for (n in listOf("secret", "Po")) assertTrue(errs.invisible(use, n).isNotEmpty(), "$n is private to Other.kt; got $errs")
        assertTrue(errs.invisible(use, "Hidden").size == 3, "each use of Hidden is reported; got $errs")
    }

    @Test
    fun suppressedVisibilityErrorsStaySilent() {
        val other = "package demo\nprivate object Po\nclass Holder { private fun hidden() {} }\n"
        val use = "package demo\n@Suppress(\"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\")\nfun f(h: Holder) {\n  val p = Po\n  h.hidden()\n}\n" +
            "fun g() = @Suppress(\"INVISIBLE_REFERENCE\") Po\n"
        val errs = errors(use, other)
        assertTrue(errs.none { use.substring(it.range.start, it.range.end) in setOf("Po", "hidden") }, "suppressed; got $errs")
    }

    @Test
    fun memberOfAClassPrivateToAnotherFile() {
        val other = "package demo\nprivate class Hidden { fun bar() = 1 }\n@Suppress(\"EXPOSED_FUNCTION_RETURN_TYPE\") fun makeHidden() = Hidden()\n"
        val use = "package demo\nfun f() { val y = makeHidden()\n  y.bar() }\n"
        val errs = errors(use, other)
        assertTrue(errs.invisible(use, "bar").isNotEmpty(), "Hidden's members are out of reach outside its file; got $errs")
    }

    @Test
    fun privateTopLevelOfTheSameFileIsVisible() {
        val use = "package demo\nprivate class Hidden\nprivate fun secret() = Hidden()\nfun f() { secret() }\n"
        val errs = errors(use)
        assertTrue(errs.isEmpty(), "a file sees its own privates; got $errs")
    }

    @Test
    fun importedPrivateNestedClass() {
        val use = "package demo\nimport demo.Top.Hidden\nimport demo.Top.Hidden.Deep\n" +
            "class Top {\n  private class Hidden { class Deep }\n  fun inside() { Deep() }\n}\n" +
            "fun outside() { Hidden(); Deep() }\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "Hidden").size == 1, "Hidden() outside Top is hidden; got $errs")
        assertTrue(errs.invisible(use, "Deep").size == 1, "Deep() is hidden outside Top only; got $errs")
    }

    @Test
    fun hiddenConstructors() {
        val use = "package demo\nclass OnlyPrivate private constructor(val x: Int) { fun copy() = OnlyPrivate(x) }\n" +
            "class Mixed private constructor(val x: Int) { constructor(s: String) : this(s.length) }\n" +
            "open class Base private constructor() { class Inner : Base() }\n" +
            "class Outside : Base()\n" +
            "fun f() { OnlyPrivate(2); Mixed(\"a\") }\n"
        val errs = errors(use)
        assertTrue(errs.invisible(use, "OnlyPrivate").size == 1, "only the outside OnlyPrivate(2) is hidden; got $errs")
        assertTrue(errs.none { use.substring(it.range.start, it.range.end) == "Mixed" }, "Mixed has a public constructor; got $errs")
        assertTrue(errs.invisible(use, "Base").size == 1, "Outside cannot call Base's private constructor, Inner can; got $errs")
    }
}
