package dev.ide.lang.kotlin

import dev.ide.lang.dom.NodeKind
import dev.ide.lang.resolve.ResolveResult
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end exercise against a real source project + the kotlin-stdlib jar: member completion after `.`
 * INCLUDING stdlib extensions (`string.trim`, `list.map`), plus source-class members, the `a.b().c`
 * inference chain, scope completion, type completion, and go-to-definition resolution.
 *
 * One analyzer is shared so the classpath extension scan runs once.
 */
class KotlinCompletionE2ETest {

    // The SYMBOL names offered (independent of how the item label renders name+params).
    private fun labels(srcDir: Path, file: String, code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.map { it.symbol?.name ?: it.label }

    @Test
    fun memberCompletionOnStringLiteralIncludesExtensions() {
        // Bare `.` offers the (large) extension set; `trim` sorts in early.
        assertTrue("trim" in labels(srcDir, "Use.kt", """fun f() { val s = "hello".| }"""), "String.trim extension expected")
        // After a few chars the set narrows — the realistic completion case.
        val upper = labels(srcDir, "Use.kt", """fun f() { val s = "hello".upper| }""")
        assertTrue("uppercase" in upper, "String.uppercase extension expected; got $upper")
    }

    @Test
    fun memberCompletionOnListIncludesMapAndFilter() {
        val items = labels(srcDir, "Use.kt", "fun f() { val xs = listOf(1, 2, 3).| }")
        assertTrue("map" in items, "Iterable.map (via List <: Iterable) expected; got ${items.take(20)}")
        assertTrue("filter" in items, "filter expected; got ${items.take(20)}")
    }

    @Test
    fun memberCompletionOnSourceClass() {
        val items = labels(srcDir, "Use.kt", "package demo\nfun f(g: Greeter) { g.| }")
        assertTrue("hello" in items, "source method 'hello'; got ${items.take(20)}")
        assertTrue("title" in items, "source property 'title'; got ${items.take(20)}")
    }

    @Test
    fun inferenceChainTypesThrough() {
        // a.b() returns B, so b().c must offer B's member 'c'.
        val items = labels(srcDir, "Use.kt", "package demo\nfun f(a: A) { a.b().| }")
        assertTrue("c" in items, "a.b().c — chained member 'c' expected; got ${items.take(20)}")
    }

    @Test
    fun scopeCompletionSeesLocalsAndTopLevel() {
        val locals = labels(srcDir, "Use.kt", "package demo\nfun use() { val localX = 1\n  localX; loc| }")
        assertTrue("localX" in locals, "local 'localX' in scope; got ${locals.take(20)}")
        val top = labels(srcDir, "Use.kt", "package demo\nfun use() { greetT| }")
        assertTrue("greetTop" in top, "top-level 'greetTop' in scope; got ${top.take(20)}")
    }

    @Test
    fun typeCompletionInTypePosition() {
        val items = labels(srcDir, "Use.kt", "fun f() { val s: Stri| }")
        assertTrue("String" in items, "type 'String' in type position; got ${items.take(20)}")
    }

    @Test
    fun prefixIsPushedDownNotJustFilteredAfter() {
        // With a non-empty prefix the symbol service must return only matching candidates (extensions,
        // members, and top-level callables) — the large-classpath perf fix. Guards against a regression that
        // re-materializes the whole set per keystroke.
        val members = labels(srcDir, "Use.kt", """fun f() { "hello".upper| }""")
        assertTrue("uppercase" in members, "matching extension expected; got ${members.take(20)}")
        assertTrue(members.none { it.startsWith("trim") }, "non-matching extensions must be filtered out; got ${members.take(20)}")
        val scope = labels(srcDir, "Use.kt", "package demo\nfun use() { greetT| }")
        assertTrue("greetTop" in scope, "matching top-level expected; got ${scope.take(20)}")
        assertTrue("answerTop" !in scope, "a non-matching top-level must be filtered out; got ${scope.take(20)}")
        assertTrue(scope.none { it == "println" }, "an unrelated stdlib top-level must not appear for prefix 'greetT'; got ${scope.take(20)}")
    }

    @Test
    fun stdlibIsImplicitWithoutDeclaringIt() {
        // Empty classpath: the project declares NO kotlin-stdlib, but the backend must still surface
        // top-level callables (println/listOf) — Kotlin's stdlib is an implicit dependency.
        val noLib = KotlinSourceAnalyzer(fakeContext(srcDir, libJars = emptyList()))
        val items = runBlocking { noLib.completeAtCaret(srcDir, "U.kt", "fun f() { printl| }") }.items.mapNotNull { it.symbol?.name }
        assertTrue("println" in items, "implicit stdlib should offer println; got ${items.take(20)}")
    }

    @Test
    fun completionItemsRenderTypes() {
        val res = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun f(g: Greeter) {\n  val n = g.hello()\n  n| }")
        }
        // The local `n` is inferred String (g.hello() returns String) → its item shows the type.
        val nItem = res.items.firstOrNull { it.symbol?.name == "n" }
        assertNotNull(nItem, "local 'n' should be offered")
        assertTrue(nItem.detail?.contains("String") == true, "local should render its type; detail=${nItem.detail}")
        // A function item renders its return type in the detail (and name+params in the label).
        val hello = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun f(g: Greeter) { g.hel| }")
        }.items.firstOrNull { it.symbol?.name == "hello" }
        assertNotNull(hello, "member 'hello' should be offered")
        assertTrue(hello.detail?.contains("String") == true, "function should render its return type; detail=${hello.detail}")
        assertTrue(hello.label.startsWith("hello("), "function label is name+params; label=${hello.label}")
    }

    @Test
    fun basicGenericsFlowThroughCallChain() {
        // listOf("") : List<String>  →  .first() : String  →  String.uppercase (extension) is offered.
        val items = labels(srcDir, "Use.kt", "fun f() { listOf(\"\").first().upper| }")
        assertTrue("uppercase" in items, "generic chain listOf(\"\").first(): String → uppercase; got ${items.take(20)}")
    }

    @Test
    fun functionTypeParametersRenderAsArrows() {
        // `map`'s `transform` parameter is a function type — its label renders `(…) -> …`, not Function1<…>.
        val map = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { listOf(\"\").ma| }") }
            .items.firstOrNull { it.symbol?.name == "map" }
        assertNotNull(map, "map should be offered")
        assertTrue(map.label.contains("->"), "function-type param should render with an arrow; label=${map.label}")
        assertTrue(!map.label.contains("Function"), "must not show Function1<…>; label=${map.label}")
    }

    @Test
    fun functionItemRendersNameWithParamsThenReturnType() {
        // `println(message: Any?)` as the label; `Unit` as the grayed detail — NOT `println` then a gap.
        val pr = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { printl| }") }
            .items.firstOrNull { it.symbol?.name == "println" && it.label.contains('(') }
        assertNotNull(pr, "println should be offered")
        assertTrue(pr.label.startsWith("println("), "label = name+params; got '${pr.label}'")
        assertTrue(pr.detail?.contains("Unit") == true, "return type as detail; got '${pr.detail}'")
    }

    private fun diagnostics(code: String): List<dev.ide.lang.dom.Diagnostic> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics
    }

    @Test
    fun lambdaParameterItIsTyped() {
        // Inside `"".let { it.<caret> }`, `it` is String → String members/extensions complete.
        assertTrue("uppercase" in labels(srcDir, "Use.kt", "fun f() { \"\".let { it.upper| } }"), "`it` should be String inside let")
    }

    @Test
    fun lambdaResultFlowsThroughLet() {
        // `let` returns the lambda's result type, so the chain continues: "".let { it } : String.
        assertTrue("uppercase" in labels(srcDir, "Use.kt", "fun f() { \"\".let { it }.upper| }"), "let returns the lambda result")
    }

    @Test
    fun unresolvedMemberIsFlagged() {
        val diags = diagnostics("package demo\nfun f(g: Greeter) { g.totallyBogusMethod() }")
        assertTrue(
            diags.any { it.severity == dev.ide.lang.dom.Severity.ERROR && it.message.contains("totallyBogusMethod") },
            "an unresolved member should be flagged; got $diags",
        )
    }

    @Test
    fun validMemberHasNoDiagnostic() {
        val diags = diagnostics("package demo\nfun f(g: Greeter) { g.hello() }")
        assertTrue(diags.none { it.message.contains("hello") }, "a valid member must not be flagged; got $diags")
    }

    @Test
    fun companionObjectMemberIsNotFlaggedUnresolved() {
        // `Palette.Red` resolves through the companion object — a `Color.Red`-style static access must NOT flag.
        assertTrue(
            diagnostics("package demo\nfun f() { val r = Palette.Red\n  println(r) }").none { it.code == "kt.unresolved" },
            "a companion-object member must resolve; got ${diagnostics("package demo\nfun f() { val r = Palette.Red\n  println(r) }")}",
        )
        // ...but a genuinely-missing static/companion member is still flagged.
        assertTrue(
            diagnostics("package demo\nfun f() { val r = Palette.Bogus\n  println(r) }")
                .any { it.code == "kt.unresolved" && it.message.contains("Bogus") },
            "a missing companion member is still flagged",
        )
    }

    @Test
    fun superMemberOnSourceSupertypeStillValidated() {
        // A SOURCE supertype is fully enumerable: a valid `super.member` resolves, a bogus one is flagged.
        assertTrue(
            diagnostics("package demo\nclass C : Base() { override fun onCreate() { super.onCreate() } }")
                .none { it.code == "kt.unresolved" },
            "super.onCreate() on a source supertype must resolve",
        )
        assertTrue(
            diagnostics("package demo\nclass C : Base() { fun g() { super.bogusXyz() } }")
                .any { it.code == "kt.unresolved" && it.message.contains("bogusXyz") },
            "a bogus super member on a source supertype is flagged",
        )
    }

    @Test
    fun superMemberOnBinarySupertypeIsNotFalselyFlagged() {
        // A binary/framework supertype reaches inherited members through a best-effort chain (boot-classpath
        // ancestors may be unread), so a `super.<member>` call is never flagged — the on-device
        // `super.onCreate(...)` (ComponentActivity) false-positive.
        val diags = diagnostics("import java.util.ArrayList\nclass C : ArrayList<String>() { fun g() { super.someInheritedThing() } }")
        assertTrue(diags.none { it.code == "kt.unresolved" }, "super on a binary supertype must not be flagged; got $diags")
    }

    @Test
    fun unresolvedBareCallIsFlagged() {
        val diags = diagnostics("fun f() { prinltn(\"x\") }") // typo for println
        assertTrue(diags.any { it.message.contains("prinltn") }, "a typo'd bare call should be flagged; got $diags")
    }

    @Test
    fun typeMismatchOnTypedPropertyIsFlagged() {
        val diags = diagnostics("val a: Int = \"\"")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("String") && it.message.contains("Int") },
            "val a: Int = \"\" should be a type mismatch; got $diags",
        )
        // expression-body function and a flat-out wrong primitive too.
        assertTrue(diagnostics("fun f(): Int = \"\"").any { it.code == "kt.typeMismatch" }, "fun f(): Int = \"\" should mismatch")
        assertTrue(diagnostics("val a: String = 5").any { it.code == "kt.typeMismatch" }, "val a: String = 5 should mismatch")
    }

    @Test
    fun validTypedDeclarationsHaveNoMismatch() {
        // exact match, integer-literal adaptation to a wider numeric type, nullable, and an inferred type.
        for (code in listOf("val a: Int = 5", "val a: Long = 5", "val a: Byte = 5", "val a: String? = \"\"", "val a = \"\"", "val a: Any = \"\"")) {
            assertTrue(diagnostics(code).none { it.code == "kt.typeMismatch" }, "valid `$code` must not mismatch; got ${diagnostics(code)}")
        }
    }

    @Test
    fun valReassignmentIsFlagged() {
        assertTrue(diagnostics("fun f() { val x = 1\n  x = 2\n  println(x) }").any { it.code == "kt.valReassign" }, "reassigning a local val")
        assertTrue(diagnostics("fun f(p: Int) { p = 2\n  println(p) }").any { it.code == "kt.valReassign" }, "reassigning a parameter")
        assertTrue(diagnostics("fun f() { var x = 1\n  x = 2\n  println(x) }").none { it.code == "kt.valReassign" }, "a var may be reassigned")
        // `val list += x` desugars to a legal `plusAssign` — must NOT be flagged.
        assertTrue(diagnostics("fun f() { val xs = mutableListOf(1)\n  xs += 2\n  println(xs) }").none { it.code == "kt.valReassign" }, "+= on a val collection is legal")
    }

    @Test
    fun missingReturnIsFlagged() {
        assertTrue(diagnostics("fun f(): Int { println(\"x\") }").any { it.code == "kt.missingReturn" }, "no return in a non-Unit block body")
        assertTrue(diagnostics("fun f(): Int { }").any { it.code == "kt.missingReturn" }, "empty body, non-Unit return")
        // valid: explicit return, Unit body, and Nothing-typed terminals (throw / TODO).
        for (ok in listOf("fun f(): Int { return 1 }", "fun f() { println(\"x\") }", "fun f(): Int { throw RuntimeException() }", "fun f(): Int { TODO() }", "fun f(): Int { while (true) {} }")) {
            assertTrue(diagnostics(ok).none { it.code == "kt.missingReturn" }, "`$ok` must not flag missing return; got ${diagnostics(ok)}")
        }
    }

    @Test
    fun unusedLocalIsFlaggedAsWarning() {
        val diags = diagnostics("fun f() { val unusedX = 1 }")
        assertTrue(
            diags.any { it.code == "kt.unusedLocal" && it.severity == dev.ide.lang.dom.Severity.WARNING && it.message.contains("unusedX") },
            "an unused local should warn; got $diags",
        )
        assertTrue(diagnostics("fun f() { val y = 1\n  println(y) }").none { it.code == "kt.unusedLocal" }, "a used local must not warn")
        assertTrue(diagnostics("fun f() { val y = 1\n  run { println(y) } }").none { it.code == "kt.unusedLocal" }, "use inside a nested block counts")
    }

    @Test
    fun sameFileClassMembersAreNotFlaggedUnresolved() {
        // The symbol service indexes disk, not the buffer — but an enclosing class's own members (in the live
        // file) must still resolve, both as bare reads and bare calls.
        val diags = diagnostics("class C {\n  val n = 1\n  fun f() { println(n)\n    helper() }\n  fun helper() {}\n}")
        assertTrue(diags.none { it.code == "kt.unresolved" }, "same-file class members must resolve; got $diags")
    }

    @Test
    fun conflictingDeclarationsAreFlagged() {
        fun conflicts(code: String) = diagnostics(code).filter { it.code == "kt.conflictingDeclaration" }
        assertEquals(2, conflicts("fun f() { val x = 1\n  val x = 2\n  println(x) }").size, "duplicate local val")
        assertEquals(2, conflicts("fun f(x: Int, x: String) { println(x) }").size, "duplicate parameter")
        assertEquals(2, conflicts("val a = 1\nval a = 2").size, "duplicate top-level property")
        // same name + same parameter TYPES conflict even when parameter names differ.
        assertEquals(2, conflicts("class C { fun g(x: Int){}\n  fun g(y: Int){} }").size, "same-signature member functions")
        // valid: nested-block shadowing, real overloads (different param types), different extension receivers.
        assertTrue(conflicts("fun f() { val x = 1\n  run { val x = 2\n    println(x) }\n  println(x) }").isEmpty(), "nested shadowing is legal")
        assertTrue(conflicts("class C { fun g(x: Int){}\n  fun g(x: String){} }").isEmpty(), "overloads differ by type")
        assertTrue(conflicts("fun String.g(){}\nfun Int.g(){}").isEmpty(), "different extension receivers")
    }

    private fun codes(code: String): List<String?> = diagnostics(code).map { it.code }

    @Test
    fun returnStatementTypeMismatchIsFlagged() {
        assertTrue("kt.typeMismatch" in codes("fun f(): Int { return \"\" }"), "return \"\" in a fun(): Int")
        assertTrue("kt.typeMismatch" !in codes("fun f(): Int { return 1 }"), "return 1 in a fun(): Int is fine")
    }

    @Test
    fun argumentCountMismatchForSameFileFunctions() {
        assertTrue("kt.argumentCount" in codes("fun g() {}\nfun f() { g(1) }"), "too many args")
        assertTrue("kt.argumentCount" in codes("fun g(a: Int, b: Int) {}\nfun f() { g(1) }"), "too few args")
        // defaults, varargs, overloads, and exact matches are all fine.
        assertTrue("kt.argumentCount" !in codes("fun g(a: Int) {}\nfun f() { g(1) }"), "exact match")
        assertTrue("kt.argumentCount" !in codes("fun g(a: Int, b: Int = 0) {}\nfun f() { g(1) }"), "defaulted param")
        assertTrue("kt.argumentCount" !in codes("fun g(vararg a: Int) {}\nfun f() { g(1, 2, 3) }"), "vararg")
        assertTrue("kt.argumentCount" !in codes("fun g() {}\nfun g(a: Int) {}\nfun f() { g(1) }"), "overload")
    }

    @Test
    fun missingInitializerIsFlagged() {
        assertTrue("kt.mustBeInitialized" in codes("val x: Int"), "top-level property needs a value")
        assertTrue("kt.mustBeInitialized" in codes("class C { val x: Int }"), "concrete-class property needs a value")
        for (ok in listOf("val x: Int = 1", "class C { lateinit var s: String }", "abstract class C { abstract val x: Int }", "val x: Int get() = 1", "fun f() { val x: Int\n  x = 1\n  println(x) }")) {
            assertTrue("kt.mustBeInitialized" !in codes(ok), "`$ok` must not flag; got ${diagnostics(ok)}")
        }
    }

    @Test
    fun unsafeNullableAccessIsFlagged() {
        assertTrue("kt.unsafeNullable" in codes("fun f(s: String?) { s.length }"), "dot-access on a nullable")
        // A safe call earlier (`s?.…`) does NOT smart-cast, so a later unsafe access must still flag.
        assertTrue(
            "kt.unsafeNullable" in codes("fun f(s: String?) { val a = s?.length; val b = s.length }"),
            "an unsafe access after a safe call must still flag",
        )
        for (ok in listOf("fun f(s: String?) { s?.length }", "fun f(s: String?) { if (s != null) s.length }", "fun f(s: String) { s.length }")) {
            assertTrue("kt.unsafeNullable" !in codes(ok), "`$ok` must not flag; got ${diagnostics(ok)}")
        }
    }

    @Test
    fun sameFileConstructorArgsAreValidated() {
        // A same-file class: arity comes from the PSI, so defaults (age) and exact counts are known.
        val p = "class P(val name: String, val age: Int = 0)\n"
        assertTrue("kt.constructorArgs" !in codes(p + "fun f() { P(\"a\") }"), "1 arg OK — age has a default")
        assertTrue("kt.constructorArgs" !in codes(p + "fun f() { P(\"a\", 1) }"), "2 args OK")
        assertTrue("kt.constructorArgs" in codes(p + "fun f() { P() }"), "0 args: 'name' is required")
        assertTrue("kt.constructorArgs" in codes(p + "fun f() { P(\"a\", 1, 2) }"), "3 args: too many")
        // a class with no explicit constructor → implicit no-arg
        assertTrue("kt.constructorArgs" in codes("class Q\nfun f() { Q(1) }"), "Q has only an implicit no-arg constructor")
        // primitive/String-family argument type mismatch against the primary constructor
        assertTrue("kt.typeMismatch" in codes(p + "fun f() { P(1) }"), "Int where String expected")
        assertTrue("kt.typeMismatch" !in codes(p + "fun f() { P(\"a\", 2) }"), "matching arg types are fine")
    }

    @Test
    fun unusedImportIsFlagged() {
        assertTrue("kt.unusedImport" in codes("import kotlin.math.PI\nfun f() {}"), "PI never used")
        assertTrue("kt.unusedImport" !in codes("import kotlin.math.PI\nfun f() { println(PI) }"), "PI used")
        assertTrue("kt.unusedImport" !in codes("import kotlin.math.*\nfun f() {}"), "star import not flagged")
    }

    @Test
    fun unreachableCodeIsFlagged() {
        assertTrue("kt.unreachable" in codes("fun f() { return\n  println(\"x\") }"), "after a bare return")
        assertTrue("kt.unreachable" !in codes("fun f(c: Boolean) { if (c) return\n  println(\"x\") }"), "conditional return doesn't end the block")
    }

    @Test
    fun unusedPrivateDeclarationIsFlagged() {
        assertTrue("kt.unusedPrivate" in codes("private fun helper() {}\nfun f() {}"), "private fun never used")
        assertTrue("kt.unusedPrivate" !in codes("private fun helper() {}\nfun f() { helper() }"), "private fun used")
        assertTrue("kt.unusedPrivate" !in codes("fun helper() {}\nfun f() {}"), "public is not flagged")
    }

    @Test
    fun varThatCouldBeValIsHinted() {
        assertTrue("kt.varCouldBeVal" in codes("fun f() { var x = 1\n  println(x) }"), "never reassigned")
        assertTrue("kt.varCouldBeVal" !in codes("fun f() { var x = 1\n  x = 2\n  println(x) }"), "reassigned with =")
        assertTrue("kt.varCouldBeVal" !in codes("fun f() { var x = 1\n  x++\n  println(x) }"), "reassigned with ++")
    }

    @Test
    fun validBareCallsAreNotFlagged() {
        // top-level (println), same-file top-level (helper), and implicit-receiver (uppercase in apply) all resolve.
        val diags = diagnostics("fun f() { println(\"x\")\n  \"\".apply { uppercase() }\n  helper() }\nfun helper() {}")
        assertTrue(diags.none { it.code == "kt.unresolved" }, "valid bare calls must not be flagged; got $diags")
    }

    @Test
    fun implicitReceiverInApplyAndRun() {
        // `T.() -> R` blocks: `this` is the receiver, so its members complete without an explicit receiver.
        assertTrue("length" in labels(srcDir, "Use.kt", "fun f() { \"\".apply { len| } }"), "apply: String is `this`")
        assertTrue("length" in labels(srcDir, "Use.kt", "fun f() { \"\".run { len| } }"), "run: String is `this`")
    }

    @Test
    fun implicitReceiverInWith() {
        // with(listOf("")) { <caret> } → `this` is List<String> (T bound from the first arg).
        assertTrue("size" in labels(srcDir, "Use.kt", "fun f() { with(listOf(\"\")) { siz| } }"), "with: receiver is `this`")
    }

    @Test
    fun scopeFunctionsAreExtensionsOnAnyReceiver() {
        // let/also/run/apply have a type-parameter receiver (`fun <T> T.let`) → they must appear on ANY
        // instance receiver as extensions, not as bare top-level names.
        assertTrue("let" in labels(srcDir, "Use.kt", "fun f() { listOf(\"\").le| }"), "let on a List instance")
        assertTrue("also" in labels(srcDir, "Use.kt", "fun f() { \"\".als| }"), "also on a String instance")
        assertTrue("apply" in labels(srcDir, "Use.kt", "fun f() { listOf(\"\").app| }"), "apply on an instance")
        // `also` returns the receiver type, so the chain resolves it: "".also{}.<String members>.
        assertTrue("uppercase" in labels(srcDir, "Use.kt", "fun f() { \"\".also { }.upper| }"), "x.also{} returns the receiver")
    }

    @Test
    fun topLevelCallableImportVisibility() {
        // println (kotlin.io) is default-imported → offered with NO import edit.
        val pr = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { printl| }") }
            .items.firstOrNull { it.symbol?.name == "println" }
        assertNotNull(pr, "println should be offered")
        assertTrue(pr.additionalEdits.isEmpty(), "println is default-imported; no import edit")
        // ln (kotlin.math) is NOT default-imported → still offered, but carries an auto-import.
        val ln = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "fun f() { ln| }") }
            .items.firstOrNull { it.symbol?.name == "ln" }
        assertNotNull(ln, "ln should be offered (with auto-import)")
        assertTrue(
            ln.additionalEdits.any { it.newText.contains("import kotlin.math.ln") },
            "unimported top-level carries an import; edits=${ln.additionalEdits.map { it.newText }}",
        )
    }

    @Test
    fun memberAccessDoesNotLeakTopLevelDeclarations() {
        // E / PI / ln are top-level kotlin.math — they are NOT members of List and must not appear at `.`.
        assertTrue("PI" !in labels(srcDir, "Use.kt", "fun f() { listOf(\"\").P| }"), "PI (top-level) leaked into List members")
        assertTrue("ln" !in labels(srcDir, "Use.kt", "fun f() { listOf(\"\").l| }"), "ln (top-level) leaked into List members")
        assertTrue("map" in labels(srcDir, "Use.kt", "fun f() { listOf(\"\").ma| }"), "real List member 'map' still present")
    }

    @Test
    fun unimportedTypeOffersAutoImport() {
        // From a different package, the source class demo.Greeter needs an import added on accept.
        val g = runBlocking { analyzer.completeAtCaret(srcDir, "Other.kt", "package other\nfun f() { Greet| }") }
            .items.firstOrNull { it.label == "Greeter" }
        assertNotNull(g, "Greeter should be suggested (with auto-import)")
        assertTrue(
            g.additionalEdits.any { it.newText.contains("import demo.Greeter") },
            "should auto-import demo.Greeter; edits=${g.additionalEdits.map { it.newText }}",
        )
    }

    @Test
    fun inScopeTypeNeedsNoImport() {
        // Same package → already visible → no import edit.
        val g = runBlocking { analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun f() { Greet| }") }
            .items.firstOrNull { it.label == "Greeter" }
        assertNotNull(g)
        assertTrue(g.additionalEdits.isEmpty(), "same-package type needs no import; edits=${g.additionalEdits.map { it.newText }}")
    }

    @Test
    fun instanceReceiverHidesConstructorAndStatics() {
        // Pair("a", 1) is an INSTANCE — its constructor (label "Pair") must not appear at the `.`.
        val onInstance = labels(srcDir, "Use.kt", "fun f() { Pair(\"a\", 1).Pai| }")
        assertTrue("Pair" !in onInstance, "constructor must not be offered on an instance; got $onInstance")
        // ...but real instance members are.
        val members = labels(srcDir, "Use.kt", "fun f() { Pair(\"a\", 1).comp| }")
        assertTrue(members.any { it.startsWith("component") }, "instance members expected; got $members")
    }

    @Test
    fun noCompletionInsideStringLiteral() {
        val items = labels(srcDir, "Use.kt", "fun f() { val s = \"ab|cd\" }")
        assertTrue(items.isEmpty(), "no completion inside string-literal text; got ${items.take(10)}")
    }

    @Test
    fun completionInsideTemplateEntry() {
        // Inside ${ ... } it IS code, so completion works.
        val items = labels(srcDir, "Use.kt", "fun f() { val s = \"x\${lis|}\" }")
        assertTrue("listOf" in items, "completion should work inside \${ } template entry; got ${items.take(20)}")
    }

    @Test
    fun resolveLocalGivesDeclaration() = runBlocking {
        val code = "package demo\nfun f() { val target = 1\n  target }"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Res.kt")))
        val parsed = analyzer.incrementalParser.parseFull(doc)
        val usageOffset = code.lastIndexOf("target")
        val node = parsed.nodeAt(usageOffset)
        val nameRef = generateSequence(node) { it.parent }.first { it.kind == NodeKind.NAME_REF }
        val result = analyzer.resolve(nameRef)
        assertTrue(result is ResolveResult.Resolved, "local 'target' should resolve")
        assertNotNull(result.symbol.declaration(), "resolved local carries its declaration node")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Greeter.kt" to "package demo\nclass Greeter {\n  fun hello(): String = \"hi\"\n  val title: String = \"t\"\n}",
                "Chain.kt" to "package demo\nclass A { fun b(): B = B() }\nclass B { fun c(): String = \"c\" }",
                "Top.kt" to "package demo\nfun greetTop() {}\nval answerTop = 42",
                // A class with a companion object (Compose `Color.Red`-style static access).
                "Palette.kt" to "package demo\nclass Palette {\n  companion object {\n    val Red: Palette = Palette()\n    fun mix(): Palette = Palette()\n  }\n}",
                // A source supertype with an overridable member (source `super.member` is fully enumerable).
                "Bases.kt" to "package demo\nopen class Base {\n  open fun onCreate() {}\n  open val tag: String = \"\"\n}",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
