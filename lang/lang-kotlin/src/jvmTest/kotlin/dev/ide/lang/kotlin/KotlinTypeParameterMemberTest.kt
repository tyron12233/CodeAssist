package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Member resolution on a bare type-parameter receiver via its declared UPPER BOUND (`t.member` where `t: T`
 * and `<T : Bound>` resolves against `Bound`) — completion, inferred type of a chained access, and the
 * unresolved-member diagnostic — plus the suppression of scope/type completion at a declaration-NAME spot
 * (`val <caret>` is an identifier the user is inventing, not a reference).
 */
class KotlinTypeParameterMemberTest {

    private fun labels(file: String, code: String): List<String> =
        runBlocking { analyzer.completeAtCaret(srcDir, file, code) }.items.map { it.symbol?.name ?: it.label }

    private fun diagnostics(code: String): List<Diagnostic> = runBlocking {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("D.kt")))
        analyzer.incrementalParser.parseFull(doc)
        analyzer.analyze(doc.file).diagnostics
    }

    // --- member completion through the upper bound ---

    @Test fun functionTypeParameterCompletesBoundMembers() {
        // `b: T`, `<T : Beverage>` — `b.` offers the interface's members.
        val items = labels("Use.kt", "package demo\nfun <T : Beverage> serve(b: T) { b.| }")
        assertTrue("idealTemperature" in items, "Beverage.idealTemperature via the T : Beverage bound; got ${items.take(20)}")
    }

    @Test fun classTypeParameterFieldCompletesBoundMembers() {
        // The reported case: a `class Mug<T : Beverage>(val beverage: T)` field, completed in a member initializer.
        val items = labels("Use.kt", "package demo\nclass Mug<T : Beverage>(val beverage: T) { val t = beverage.| }")
        assertTrue("idealTemperature" in items, "field of type T resolves via the bound; got ${items.take(20)}")
    }

    @Test fun boundMemberAccessInfersItsType() {
        // `beverage.idealTemperature : Int`, so the property `t` completes as an Int (chain resolves through the bound).
        val res = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun <T : Beverage> serve(b: T) {\n  val t = b.idealTemperature\n  t| }")
        }
        val t = res.items.firstOrNull { it.symbol?.name == "t" }
        assertNotNull(t, "local 't' should be offered")
        assertTrue(t.detail?.contains("Int") == true, "t = b.idealTemperature should be Int; detail=${t.detail}")
    }

    @Test fun whereClauseBoundResolves() {
        val items = labels("Use.kt", "package demo\nfun <T> serve(b: T) where T : Beverage { b.| }")
        assertTrue("idealTemperature" in items, "a `where T : Beverage` bound resolves too; got ${items.take(20)}")
    }

    // --- diagnostics against the bound ---

    @Test fun validBoundMemberIsNotFlagged() {
        val diags = diagnostics("package demo\nfun <T : Beverage> serve(b: T) { println(b.idealTemperature) }")
        assertTrue(diags.none { it.code == "kt.unresolved" }, "a member of the bound must resolve; got $diags")
    }

    @Test fun bogusBoundMemberIsFlagged() {
        val diags = diagnostics("package demo\nfun <T : Beverage> serve(b: T) { println(b.nonExistentThing) }")
        assertTrue(
            diags.any { it.code == "kt.unresolved" && it.message.contains("nonExistentThing") },
            "a member NOT on the bound is unresolved; got $diags",
        )
    }

    @Test fun bogusBoundMemberOnClassFieldIsFlagged() {
        // The reported case: a `class Mug<T : Beverage>(val beverage: T)` field — `beverage.anything` must error.
        val body = "class Mug<T : Beverage>(val beverage: T)"
        assertTrue(
            diagnostics("package demo\n$body {\n  fun f() { println(beverage.anything) }\n}")
                .any { it.code == "kt.unresolved" && it.message.contains("anything") },
            "beverage.anything (field of type T) must be unresolved",
        )
        // ...and a real bound member is clean, in a method body AND a property initializer.
        assertTrue(
            diagnostics("package demo\n$body {\n  fun f() { println(beverage.idealTemperature) }\n}").none { it.code == "kt.unresolved" },
            "beverage.idealTemperature must resolve in a method body",
        )
        assertTrue(
            diagnostics("package demo\n$body {\n  val t = beverage.idealTemperature\n}").none { it.code == "kt.unresolved" },
            "beverage.idealTemperature must resolve in a property initializer",
        )
    }

    @Test fun unboundedTypeParameterMemberIsNotFalselyFlagged() {
        // No bound (`<T>` = `T : Any?`) — the concrete members can't be enumerated, so back off (never flag).
        val diags = diagnostics("package demo\nfun <T> serve(b: T) { println(b.whatever) }")
        assertTrue(diags.none { it.code == "kt.unresolved" }, "an unbounded T receiver must not be flagged; got $diags")
    }

    // --- declaration-name completion suppression ---

    @Test fun declarationNameOffersNoScopeOrTypeCandidates() {
        // Naming a local `val`: the class-name / import / scope-symbol candidates must NOT appear.
        val v = labels("Use.kt", "package demo\nfun f() { val | }")
        assertTrue(v.isEmpty(), "no completion when naming a val; got ${v.take(20)}")
        // A partially typed name is still just a name being invented — `Str` must not offer `String`.
        val partial = labels("Use.kt", "package demo\nfun f() { val Str| }")
        assertTrue("String" !in partial, "a type name must not be offered as a variable name; got ${partial.take(20)}")
        // `var`, function, and class names are the same.
        assertTrue(labels("Use.kt", "package demo\nfun f() { var | }").isEmpty(), "naming a var")
        assertTrue("String" !in labels("Use.kt", "package demo\nfun foo|"), "naming a top-level fun")
        assertTrue("String" !in labels("Use.kt", "package demo\nclass Ba|"), "naming a class")
    }

    @Test fun referenceInInitializerStillCompletes() {
        // The initializer of a `val` IS a reference position — completion must still work there (guards against
        // over-suppression bleeding past the name).
        val items = labels("Use.kt", "package demo\nfun f() { val x = listO| }")
        assertTrue("listOf" in items, "an initializer reference must still complete; got ${items.take(20)}")
    }

    // --- member CALLS + go-to-definition through the bound ---

    @Test fun boundMemberCallCompletesAndInfersReturnType() {
        assertTrue("brew" in labels("Use.kt", "package demo\nfun <T : Beverage> serve(b: T) { b.br| }"), "bound method offered")
        val res = runBlocking {
            analyzer.completeAtCaret(srcDir, "Use.kt", "package demo\nfun <T : Beverage> serve(b: T) {\n  val r = b.brew()\n  r| }")
        }
        val r = res.items.firstOrNull { it.symbol?.name == "r" }
        assertNotNull(r, "local 'r' should be offered")
        assertTrue(r.detail?.contains("String") == true, "b.brew() : String → r is String; detail=${r.detail}")
    }

    @Test fun goToDefinitionResolvesBoundMember() = runBlocking {
        val code = "package demo\nfun <T : Beverage> serve(b: T) { b.idealTemperature }"
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("Nav.kt")))
        val parsed = analyzer.incrementalParser.parseFull(doc)
        val off = code.lastIndexOf("idealTemperature")
        val node = parsed.nodeAt(off)
        val nameRef = generateSequence(node) { it.parent }.first { it.kind == dev.ide.lang.dom.NodeKind.NAME_REF }
        val result = analyzer.resolve(nameRef)
        assertTrue(result is dev.ide.lang.resolve.ResolveResult.Resolved, "b.idealTemperature should resolve to the bound member")
        assertTrue(result.symbol.name == "idealTemperature", "resolved to idealTemperature; got ${result.symbol.name}")
    }

    companion object {
        val srcDir: Path = tempProject(
            mapOf(
                "Beverage.kt" to "package demo\nsealed interface Beverage {\n  val idealTemperature: Int\n  fun brew(): String\n}\n",
            ),
        )
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
