package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Resolution + diagnostics through Kotlin objects, anonymous objects, anonymous functions, companion members,
 * and local (in-body) classes/objects. Each construct must (a) resolve — proven POSITIVELY by a deliberate
 * `kt.typeMismatch` that only fires if the member/return was actually typed — and (b) never false-positive
 * (`kt.unresolved`) on correct code, per the parse-only model's conservative contract.
 */
class KotlinObjectScopeTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    private fun completions(fileName: String, code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, fileName, code) }.items.mapNotNull { it.symbol?.name }

    private fun unresolved(diags: List<Diagnostic>) = diags.filter { it.code == "kt.unresolved" }
    private fun mismatch(diags: List<Diagnostic>) = diags.filter { it.code == "kt.typeMismatch" }

    // --- anonymous objects ------------------------------------------------------------------------

    @Test
    fun anonymousObjectOwnMemberResolves() {
        val d = diagnose(
            "AnonOwn.kt",
            "package demo\nfun f() {\n  val x = object { val a: Int = 1; fun m(): String = \"\" }\n" +
                "  val ok: Int = x.a\n  val bad: Boolean = x.a\n}",
        )
        assertTrue(unresolved(d).none { it.message.contains(": a") }, "`x.a` on an anonymous object must resolve; got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(), "`val bad: Boolean = x.a` (a is Int) must flag a type mismatch — proving x.a typed; got $d")
    }

    @Test
    fun anonymousObjectMissingMemberIsFlagged() {
        val d = diagnose(
            "AnonMissing.kt",
            "package demo\nfun f() {\n  val x = object { val a: Int = 1 }\n  val y = x.nope\n}",
        )
        assertTrue(unresolved(d).any { it.message.contains("nope") }, "a nonexistent member of a fully-known anonymous object must be flagged; got $d")
    }

    @Test
    fun anonymousObjectSupertypeMemberInBodyResolves() {
        val d = diagnose(
            "AnonSuper.kt",
            "package demo\ninterface Base { fun helper(): Int = 42 }\n" +
                "fun f() {\n  val g = object : Base {\n    fun use(): Int = helper()\n    fun useThis(): Int = this.helper()\n  }\n  g.use()\n}",
        )
        assertTrue(unresolved(d).isEmpty(), "an inherited `helper()` (bare and via `this`) inside an anonymous object body must resolve; got ${unresolved(d)}")
    }

    @Test
    fun anonymousObjectMemberCompletion() {
        val items = completions(
            "AnonComplete.kt",
            "package demo\nfun f() {\n  val x = object { val alpha: Int = 1; fun beta(): String = \"\" }\n  x.al|\n}",
        )
        assertTrue("alpha" in items, "completion after `x.` on an anonymous object must offer its member `alpha`; got $items")
    }

    @Test
    fun anonymousObjectReturnedFromPublicFunctionApproximatesToAny() {
        // Kotlin approximates the inferred return type of a NON-local, NON-private declaration whose body is an
        // anonymous object to a denotable supertype (here `Any`, no declared supertype) — the anonymous type
        // isn't nameable outside its scope. So `giveMe().player` (and `a.player`) is unresolved.
        val d = diagnose(
            "AnonEscape.kt",
            "package demo\nfun giveMe() = object { val player = \"x\" }\n" +
                "fun test() {\n  val a = giveMe()\n  a.player\n}",
        )
        assertTrue(unresolved(d).any { it.message.contains("player") },
            "a member of an anonymous object escaping via a public function (return type `Any`) must be unresolved; got $d")
    }

    @Test
    fun anonymousObjectReturnedFromPrivateFunctionKeepsItsType() {
        // A PRIVATE (or local) declaration keeps the exact anonymous type — its members stay accessible.
        val d = diagnose(
            "AnonPrivate.kt",
            "package demo\nprivate fun giveMe() = object { val player = \"x\" }\n" +
                "fun test() {\n  val a = giveMe()\n  val bad: Int = a.player\n}",
        )
        assertTrue(unresolved(d).none { it.message.contains("player") },
            "a private function's anonymous-object member must resolve; got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(),
            "`val bad: Int = a.player` (player is String) must flag a mismatch — proving a.player typed; got $d")
    }

    @Test
    fun expressionBodyReturnTypeIsInferredForCallChain() {
        // A member chain off an expression-body function with no declared return type resolves.
        val d = diagnose(
            "ExprBody.kt",
            "package demo\nfun greet() = \"hi\"\nfun t() { val bad: Boolean = greet().length }",
        )
        assertTrue(unresolved(d).isEmpty(), "`greet().length` must resolve (greet() is String); got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(),
            "`val bad: Boolean = greet().length` (length is Int) must flag a mismatch — proving greet() typed String; got $d")
    }

    @Test
    fun callableReferenceToMemberIsNotFlagged() {
        // `Person::age` — the referenced member is resolved against the receiver TYPE, not the bare-name scope,
        // so it must never be reported as an unresolved bare reference.
        val d = diagnose(
            "CallableRef.kt",
            "package demo\ndata class Person(val name: String, val age: Int)\n" +
                "fun f() { listOf(Person(\"a\", 1)).maxByOrNull(Person::age) }",
        )
        assertTrue(unresolved(d).isEmpty(), "a callable reference `Person::age` must not be flagged unresolved; got ${unresolved(d)}")
    }

    @Test
    fun localAnonymousObjectTypeRendersReadably() {
        // A LOCAL var keeps the anonymous type (its members resolve in scope), but it must display as
        // `<anonymous : Super>` — never the internal `$L` synthetic key.
        val d = diagnose(
            "AnonRender.kt",
            "package demo\nopen class Doc\nfun f() {\n  val article = object : Doc() { val n = 1 }\n  val bad: Int = article\n}",
        )
        assertTrue(
            mismatch(d).any { it.message.contains("<anonymous") && !it.message.contains("\$L") },
            "an anonymous-object local's type must render as `<anonymous …>`, not the `\$L` key; got $d",
        )
    }

    // --- anonymous functions ----------------------------------------------------------------------

    @Test
    fun anonymousFunctionResultTypeIsInferred() {
        val d = diagnose(
            "AnonFn.kt",
            "package demo\nfun f() {\n  val g = fun(x: Int): Int = x * 2\n  val ok: Int = g(3)\n  val bad: Boolean = g(3)\n}",
        )
        assertTrue(unresolved(d).isEmpty(), "invoking an anonymous-function value must not be flagged unresolved; got ${unresolved(d)}")
        assertTrue(diagnose("AnonFn.kt", "package demo\nfun f() {\n  val g = fun(x: Int): Int = x * 2\n  val bad: Boolean = g(3)\n}")
            .any { it.code == "kt.typeMismatch" }, "`val bad: Boolean = g(3)` must flag a mismatch — proving g(3) typed Int; got $d")
        assertTrue(diagnose("AnonFn.kt", "package demo\nfun f() {\n  val g = fun(x: Int): Int = x * 2\n  val bad: Boolean = g(3)\n}")
            .none { it.code == "kt.notCallable" }, "a function-typed value must be invocable; got $d")
    }

    // --- companion members ------------------------------------------------------------------------

    @Test
    fun companionConstantChainTypes() {
        val base = "package demo\nclass C { companion object { const val NAME: String = \"x\"\n  fun make(): C = C() } }\n"
        assertTrue(diagnose("Comp1.kt", base + "fun f() { val bad: Boolean = C.NAME.length }").any { it.code == "kt.typeMismatch" },
            "`C.NAME.length` (String.length is Int) vs Boolean must mismatch — proving the companion-constant chain typed")
        assertTrue(diagnose("Comp2.kt", base + "fun f() { val ok: Int = C.NAME.length }").none { it.code == "kt.unresolved" },
            "`C.NAME.length` must resolve with no unresolved reference")
    }

    @Test
    fun companionFactoryCallTypes() {
        val base = "package demo\nclass C { companion object { fun make(): C = C() }\n  fun instanceMethod(): Int = 1 }\n"
        assertTrue(diagnose("Comp3.kt", base + "fun f() { val bad: Boolean = C.make() }").any { it.code == "kt.typeMismatch" },
            "`C.make()` returns C, so `val bad: Boolean = C.make()` must mismatch — proving the companion method resolved")
        assertTrue(diagnose("Comp4.kt", base + "fun f() { val n: Int = C.make().instanceMethod() }").none { it.code == "kt.unresolved" },
            "`C.make().instanceMethod()` must resolve end to end")
    }

    @Test
    fun bareCompanionMemberInsideClassResolves() {
        val d = diagnose(
            "CompBare.kt",
            "package demo\nclass C {\n  companion object { const val NAME: String = \"x\" }\n  fun use(): Int = NAME.length\n}",
        )
        assertTrue(unresolved(d).isEmpty(), "a bare companion member (`NAME`) read inside the class must resolve; got ${unresolved(d)}")
    }

    // --- local classes / objects ------------------------------------------------------------------

    @Test
    fun localClassConstructorAndMemberResolve() {
        val d = diagnose(
            "LocalClass.kt",
            "package demo\nfun f() {\n  class Local(val v: Int)\n  val x = Local(1)\n  val ok: Int = x.v\n  val bad: Boolean = x.v\n}",
        )
        assertTrue(unresolved(d).isEmpty(), "`Local(1)` and `x.v` for a local class must resolve; got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(), "`val bad: Boolean = x.v` (v is Int) must mismatch — proving the local class member typed; got $d")
    }

    @Test
    fun localClassTypeReferenceResolves() {
        val d = diagnose(
            "LocalType.kt",
            "package demo\nfun f() {\n  class Local(val v: Int)\n  val x: Local = Local(1)\n}",
        )
        assertTrue(unresolved(d).isEmpty(), "a `val x: Local` type reference to a local class must not be flagged unresolved; got ${unresolved(d)}")
    }

    @Test
    fun localObjectMemberResolves() {
        val d = diagnose(
            "LocalObj.kt",
            "package demo\nfun f() {\n  object Helper { fun help(): Int = 1 }\n  val bad: Boolean = Helper.help()\n}",
        )
        assertTrue(unresolved(d).isEmpty(), "a local `object Helper`'s member must resolve; got ${unresolved(d)}")
        assertTrue(mismatch(d).isNotEmpty(), "`val bad: Boolean = Helper.help()` (returns Int) must mismatch — proving the local object member typed; got $d")
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
