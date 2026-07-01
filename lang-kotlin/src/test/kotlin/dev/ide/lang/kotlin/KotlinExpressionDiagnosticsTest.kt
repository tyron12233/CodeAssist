package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Expression-level semantic diagnostics that consult the resolver: a useless cast (`x as String` where `x` is
 * already `String`), a useless elvis (`x!! ?: y`), and a value returned from a `Unit`-returning function
 * (`fun f() { return 5 }`). Each error/warning case must be flagged and each valid counterpart left alone,
 * without false positives over the parse-only model.
 */
class KotlinExpressionDiagnosticsTest {

    private fun diagnose(fileName: String, code: String): List<Diagnostic> {
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve(fileName)))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    @Test
    fun uselessCastToSameTypeIsFlagged() {
        val diags = diagnose("UselessCast.kt", "package demo\nfun f(s: String) { val x = s as String }")
        assertTrue(
            diags.any { it.code == "kt.uselessCast" },
            "casting a String to String should be flagged useless; got $diags",
        )
    }

    @Test
    fun narrowingCastIsNotFlagged() {
        val diags = diagnose("RealCast.kt", "package demo\nfun f(a: Any) { val x = a as String }")
        assertTrue(
            diags.none { it.code == "kt.uselessCast" },
            "a narrowing `Any as String` cast must not be flagged; got $diags",
        )
    }

    @Test
    fun safeCastIsNotFlagged() {
        val diags = diagnose("SafeCast.kt", "package demo\nfun f(s: String) { val x = s as? String }")
        assertTrue(
            diags.none { it.code == "kt.uselessCast" },
            "a safe `as?` cast must not be flagged as a useless cast; got $diags",
        )
    }

    @Test
    fun elvisOnNotNullAssertedOperandIsFlagged() {
        val diags = diagnose("UselessElvis.kt", "package demo\nfun f(s: String?) { val x = s!! ?: \"\" }")
        assertTrue(
            diags.any { it.code == "kt.uselessElvis" },
            "`s!! ?: \"\"` should flag a useless elvis; got $diags",
        )
    }

    @Test
    fun elvisOnNullableOperandIsNotFlagged() {
        val diags = diagnose("OkElvis.kt", "package demo\nfun f(s: String?) { val x = s ?: \"\" }")
        assertTrue(
            diags.none { it.code == "kt.uselessElvis" },
            "`s ?: \"\"` on a genuinely nullable value must not be flagged; got $diags",
        )
    }

    @Test
    fun returningValueFromImplicitUnitFunctionIsFlagged() {
        val diags = diagnose("UnitReturn.kt", "package demo\nfun f() { return 5 }")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("Unit") },
            "returning a value from a Unit function should be flagged; got $diags",
        )
    }

    @Test
    fun returningValueFromExplicitUnitFunctionIsFlagged() {
        val diags = diagnose("UnitReturn2.kt", "package demo\nfun f(): Unit { return 5 }")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" && it.message.contains("Unit") },
            "returning a value from an explicit-Unit function should be flagged; got $diags",
        )
    }

    @Test
    fun matchingReturnTypeIsNotFlagged() {
        val diags = diagnose("IntReturn.kt", "package demo\nfun f(): Int { return 5 }")
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "a correctly-typed return must not be flagged; got $diags",
        )
    }

    @Test
    fun valuelessReturnFromUnitFunctionIsNotFlagged() {
        val diags = diagnose("BareReturn.kt", "package demo\nfun f() { if (true) return\n  println(1) }")
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "a bare `return` in a Unit function must not be flagged; got $diags",
        )
    }

    @Test
    fun chainedStringExtensionAfterMostSpecificOverloadIsNotFlagged() {
        // `String.removePrefix(CharSequence): String` and `CharSequence.removePrefix(CharSequence): CharSequence`
        // both fit a String receiver + String arg; only the String overload's return type lets the trailing
        // `String`-only extension `uppercase()` (absent on CharSequence) resolve. The most-specific-receiver
        // tiebreak must pick the String overload deterministically (not by HashSet candidate order).
        val code = "package demo\nfun main() { val s = \"\"\n  s.removePrefix(\"\").uppercase() }"
        val diags = diagnose("Chain.kt", code)
        assertTrue(
            diags.none { it.code == "kt.unresolved" && "uppercase" in it.message },
            "`s.removePrefix(\"\").uppercase()` must resolve via the String overload, not be flagged unresolved; got $diags",
        )
    }

    @Test
    fun enumConstantWhenWithNullBranchIsNotFlagged() {
        // The reported bug: `SomeEnum.A` was mis-inferred as a nested type named "A", so the when-expression
        // body (whose branches are SomeEnum constants + null) failed the SomeEnum? return-type check.
        val code = "package demo\nenum class SomeEnum { A, B }\n" +
            "fun test(name: String): SomeEnum? = when (name) {\n  \"A\" -> SomeEnum.A\n  \"B\" -> SomeEnum.B\n  else -> null\n}\n"
        val diags = diagnose("EnumWhen.kt", code)
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "a when of enum constants + null assigned to EnumType? must not be a type mismatch; got $diags",
        )
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "enum constants SomeEnum.A/B must resolve, not be flagged unresolved; got $diags",
        )
    }

    @Test
    fun enumConstantBodyIsNotFlaggedUnresolved() {
        val code = "package demo\nenum class E { A, B }\nfun t(n: String): E = when (n) {\n  \"A\" -> E.A\n  else -> E.B\n}\n"
        val diags = diagnose("EnumBody.kt", code)
        assertTrue(
            diags.none { it.code == "kt.unresolved" },
            "E.A / E.B (enum constants) must resolve; got $diags",
        )
        assertTrue(
            diags.none { it.code == "kt.typeMismatch" },
            "an exhaustive enum when returning the enum type must not be a type mismatch; got $diags",
        )
    }

    @Test
    fun whenWithNullBranchAssignedToNullableIsNotFlagged() {
        val diags = diagnose("IntWhen.kt", "package demo\nfun f(x: Int): Int? = when (x) {\n  1 -> 1\n  else -> null\n}\n")
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "Int? = when{1->1; else->null} must not be flagged; got $diags")
    }

    @Test
    fun ifWithNullBranchAssignedToNullableIsNotFlagged() {
        val diags = diagnose("IfNull.kt", "package demo\nfun g(c: Boolean): String? = if (c) \"a\" else null")
        assertTrue(diags.none { it.code == "kt.typeMismatch" }, "String? = if(c) \"a\" else null must not be flagged; got $diags")
    }

    @Test
    fun realExpressionBodyTypeMismatchIsStillFlagged() {
        // The fix must not silence genuine mismatches: a String body for an Int-returning function.
        val diags = diagnose("BadBody.kt", "package demo\nfun f(): Int = \"x\"")
        assertTrue(
            diags.any { it.code == "kt.typeMismatch" },
            "a String expression body for an Int function must still be a type mismatch; got $diags",
        )
    }

    // --- redundancy / unused (2026-06-30) ---

    @Test
    fun unusedFunctionParameterIsFlagged() {
        val diags = diagnose("UnusedParam.kt", "package demo\nfun f(used: Int, unused: Int) { println(used) }")
        assertTrue(diags.any { it.code == "kt.unusedParameter" && it.message.contains("'unused'") }, "got $diags")
        assertTrue(diags.none { it.code == "kt.unusedParameter" && it.message.contains("'used'") }, "a used param must not flag; got $diags")
    }

    @Test
    fun unusedParameterNotFlaggedOnOverrideOrUnderscoreOrMain() {
        assertTrue(
            diagnose("Ov.kt", "package demo\nopen class B { open fun f(x: Int) {} }\nclass C : B() { override fun f(x: Int) {} }")
                .none { it.code == "kt.unusedParameter" },
            "an override's parameter must not be flagged (signature is contractual)",
        )
        assertTrue(diagnose("Main.kt", "package demo\nfun main(args: Array<String>) {}").none { it.code == "kt.unusedParameter" }, "main(args) must not flag")
    }

    @Test
    fun unusedLambdaParameterIsFlagged() {
        val diags = diagnose("Lam.kt", "package demo\nfun f() { listOf(1).forEach { x -> println(\"hi\") } }")
        assertTrue(diags.any { it.code == "kt.unusedParameter" && it.message.contains("'x'") }, "got $diags")
    }

    @Test
    fun redundantStringTemplateBracesFlagged() {
        val diags = diagnose("Tmpl.kt", "package demo\nfun f(name: String) { println(\"Hello \${name}\") }")
        assertTrue(diags.any { it.code == "kt.redundantStringTemplate" }, "got $diags")
    }

    @Test
    fun necessaryStringTemplateBracesNotFlagged() {
        assertTrue(
            diagnose("Tmpl2.kt", "package demo\nfun f(name: String) { println(\"\${name}s\") }").none { it.code == "kt.redundantStringTemplate" },
            "braces before an identifier char are required (`\${name}s` != `\$names`)",
        )
        assertTrue(
            diagnose("Tmpl3.kt", "package demo\nclass P(val x: Int)\nfun f(p: P) { println(\"\${p.x}\") }").none { it.code == "kt.redundantStringTemplate" },
            "a member-access template entry must keep its braces",
        )
    }

    @Test
    fun redundantNotNullOnNonNullFlagged() {
        assertTrue(
            diagnose("Bang.kt", "package demo\nfun f(s: String) { val y = s!!.length }").any { it.code == "kt.redundantNotNull" },
            "`!!` on a non-null `String` parameter is redundant",
        )
    }

    @Test
    fun notNullAndSafeCallOnNullableNotFlagged() {
        assertTrue(diagnose("Bang2.kt", "package demo\nfun f(s: String?) { val y = s!!.length }").none { it.code == "kt.redundantNotNull" }, "`!!` on a nullable is needed")
        assertTrue(diagnose("Safe2.kt", "package demo\nfun f(s: String?) { val y = s?.length }").none { it.code == "kt.redundantSafeCall" }, "`?.` on a nullable is needed")
    }

    @Test
    fun redundantSafeCallOnNonNullFlagged() {
        assertTrue(
            diagnose("Safe.kt", "package demo\nfun f(s: String) { val y = s?.length }").any { it.code == "kt.redundantSafeCall" },
            "`?.` on a non-null `String` parameter is redundant",
        )
    }

    @Test
    fun notNullOnInferredLocalNotFlagged() {
        // An inferred local could be a Java platform type; `!!` must NOT be flagged without an explicit non-null type.
        assertTrue(
            diagnose("Inf.kt", "package demo\nfun src(): String = \"x\"\nfun f() { val s = src(); val y = s!!.length }").none { it.code == "kt.redundantNotNull" },
            "an inferred local's `!!` must not be flagged (platform-type conservatism)",
        )
    }

    // --- type-argument count (2026-06-30) ---

    @Test
    fun wrongTypeArgumentCountFlagged() {
        assertTrue(
            diagnose("TA1.kt", "package demo\nfun f() { val m: Map<Int> = TODO() }").any { it.code == "kt.typeArgumentCount" },
            "Map<Int> needs 2 type arguments",
        )
    }

    @Test
    fun correctTypeArgumentCountNotFlagged() {
        assertTrue(
            diagnose("TA2.kt", "package demo\nfun f() { val m: Map<Int, String> = TODO() }").none { it.code == "kt.typeArgumentCount" },
            "Map<Int, String> is correct",
        )
    }

    @Test
    fun typeArgsOnNonGenericSourceTypeFlagged() {
        assertTrue(
            diagnose("TA3.kt", "package demo\nclass Plain\nfun f() { val p: Plain<Int> = TODO() }").any { it.code == "kt.typeArgumentCount" },
            "a non-generic source class must not take type arguments",
        )
    }

    @Test
    fun genericSourceTypeWithCorrectArgsNotFlagged() {
        assertTrue(
            diagnose("TA4.kt", "package demo\nclass Box<T>(val v: T)\nfun f() { val b: Box<Int> = TODO() }").none { it.code == "kt.typeArgumentCount" },
            "Box<Int> is the correct arity",
        )
    }

    // --- incomparable equality (2026-06-30) ---

    @Test
    fun incomparableLiteralComparisonFlagged() {
        assertTrue(
            diagnose("Cmp1.kt", "package demo\nfun f() { val b = \"x\" == 5 }").any { it.code == "kt.incomparableEquality" },
            "`String == Int` can never be true",
        )
    }

    @Test
    fun incomparableEnumsFlagged() {
        val diags = diagnose("Cmp2.kt", "package demo\nenum class A { X }\nenum class B { Y }\nfun f(a: A, b: B) { val r = a == b }")
        assertTrue(diags.any { it.code == "kt.incomparableEquality" }, "two different enums can never be equal; got $diags")
    }

    @Test
    fun comparableAndNullAndNumericComparisonsNotFlagged() {
        assertTrue(
            diagnose("Cmp3.kt", "package demo\nfun f(a: Any?, s: String) { val r1 = a == s; val r2 = s == \"x\"; val r3 = s == null }")
                .none { it.code == "kt.incomparableEquality" },
            "Any/same-type/null comparisons are valid",
        )
        assertTrue(
            diagnose("Cmp4.kt", "package demo\nfun f(x: Int, y: Long) { val r = x == y }").none { it.code == "kt.incomparableEquality" },
            "numeric/numeric comparisons back off (literal adaptation)",
        )
    }

    companion object {
        val srcDir: Path = tempProject(mapOf("Seed.kt" to "package demo\n"))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
    }
}
