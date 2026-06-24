package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Overload-aware call applicability ([KotlinSourceAnalyzer]'s `callNotApplicable`), plus the assignment
 * type-mismatch, `when`-exhaustiveness, and non-callable checks added alongside it.
 *
 * The motivating case: a call to an OVERLOADED function (like Compose's `Text(...)`) whose arguments fit no
 * overload — `Text("x", 1231)` where the 2nd parameter is a `Modifier`, not an `Int`. The narrow same-file
 * arity check skips overloaded callees and the constructor checks don't apply, so this previously went
 * unreported. The check must flag it while staying false-positive-free over the parse-only model (back off on
 * unknown defaults, functional arguments, and unresolved types).
 */
class KotlinCallApplicabilityTest {

    private fun diagnose(code: String): List<Diagnostic> {
        val srcDir = tempProject(mapOf("A.kt" to code))
        val analyzer = KotlinSourceAnalyzer(fakeContext(srcDir))
        val doc = SnippetDoc(code, DiskFile(srcDir.resolve("A.kt")))
        return runBlocking { analyzer.incrementalParser.parseFull(doc); analyzer.analyze(doc.file).diagnostics }
    }

    // ---- overload applicability: argument TYPE ----

    @Test
    fun overloadWrongArgTypeIsFlagged() {
        // Mirrors Text("x", 1231): two overloads, neither takes (String, Int). The best-fitting overload
        // (matched the leading String) blames the 2nd argument.
        val d = diagnose(
            """
            package p
            fun Show(text: String) {}
            fun Show(text: String, suffix: String) {}
            fun f() { Show("x", 1231) }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == "kt.typeMismatch" && it.message.contains("Int") && it.message.contains("String") },
            "Show(\"x\", 1231) fits no overload; the Int argument must be flagged; got $d",
        )
    }

    @Test
    fun validOverloadCallIsNotFlagged() {
        // The 2-arg overload accepts (String, Int) exactly — nothing to flag.
        val d = diagnose(
            """
            package p
            fun Show(text: String) {}
            fun Show(text: String, n: Int) {}
            fun f() { Show("x", 5) }
            """.trimIndent(),
        )
        assertTrue(
            d.none { it.code == "kt.typeMismatch" || it.code == "kt.argumentCount" },
            "Show(\"x\", 5) matches the 2-arg overload; must NOT be flagged; got $d",
        )
    }

    @Test
    fun functionalArgumentIsNotFlagged() {
        // A lambda bound to a `() -> Unit` parameter must never be type-checked (its inferred shape vs. the
        // expected function type is too imprecise) — the Compose `Button(onClick = {}, ...)` pattern.
        val d = diagnose(
            """
            package p
            fun A(block: () -> Unit) {}
            fun A(block: () -> Unit, n: Int) {}
            fun f() { A({}, 5) }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.typeMismatch" }, "a functional argument must not be flagged; got $d")
    }

    @Test
    fun multiOverloadWhereOneOverloadMatchesIsNotFlagged() {
        // Mirrors Compose's `items(list, key = { it.id }) { … }`: two same-arity overloads (an Int-based one and
        // a generic List-based one) + a named functional argument + a trailing lambda. The first positional
        // argument is a List, so the Int overload has a leading type mismatch — but the List overload accepts
        // the call, so NOTHING may be flagged. Locks in "one applicable overload suppresses the check".
        val d = diagnose(
            """
            package p
            class Item(val id: String)
            fun feed(count: Int, key: ((Int) -> Any)? = null, content: (Int) -> Unit = {}) {}
            fun <T> feed(items: List<T>, key: ((T) -> Any)? = null, content: (T) -> Unit = {}) {}
            fun f(xs: List<Item>) { feed(xs, key = { it.id }) { item -> } }
            """.trimIndent(),
        )
        assertTrue(
            d.none { it.code == "kt.typeMismatch" || it.code == "kt.argumentCount" },
            "feed(List, key = {…}) {…} matches the List overload; must NOT be flagged; got $d",
        )
    }

    @Test
    fun numericLiteralAdaptationIsNotFlagged() {
        // An Int literal adapts to a Long parameter (Kotlin's literal coercion); the conservative rule excuses
        // numeric/numeric, so this must not be flagged.
        val d = diagnose(
            """
            package p
            fun N(a: String, n: Long) {}
            fun N(a: String) {}
            fun f() { N("x", 5) }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.typeMismatch" }, "an Int literal for a Long parameter must not be flagged; got $d")
    }

    // ---- overload applicability: TOO MANY arguments ----

    @Test
    fun tooManyArgumentsForOverloadedFunctionIsFlagged() {
        // No `Two` overload takes 3 arguments — the same-file unique-arity check can't judge an overloaded name,
        // so the overload-aware check owns this.
        val d = diagnose(
            """
            package p
            fun Two(a: String) {}
            fun Two(a: String, b: String) {}
            fun f() { Two("a", "b", "c") }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == "kt.argumentCount" && it.message.contains("Too many") },
            "Two(\"a\", \"b\", \"c\") exceeds every overload's arity; got $d",
        )
    }

    @Test
    fun varargOverloadAbsorbsExtraArgs() {
        // A vararg makes arity open — extra arguments must NOT be flagged as "too many".
        val d = diagnose(
            """
            package p
            fun V(first: String, vararg rest: String) {}
            fun f() { V("a", "b", "c", "d") }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.argumentCount" }, "a vararg absorbs extra arguments; got $d")
    }

    // ---- trailing lambda with no functional parameter ----

    @Test
    fun trailingLambdaWithNoFunctionalParameterIsFlagged() {
        // Mirrors `Text("Title") {}`: the last parameter is a non-functional type (here `Style`, like material3's
        // `style: TextStyle`), so Kotlin's trailing lambda has nowhere to land.
        val d = diagnose(
            """
            package p
            class Style
            fun Show(text: String, style: Style = Style()) {}
            fun f() { Show("Title") {} }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == "kt.argumentCount" && it.message.contains("trailing lambda") },
            "Show(\"Title\") {} has no function-typed parameter for the lambda; got $d",
        )
    }

    @Test
    fun validTrailingLambdaIsNotFlagged() {
        // The Compose content-slot pattern: a functional last parameter accepts the trailing lambda.
        val d = diagnose(
            """
            package p
            import androidx.compose.runtime.Composable
            @Composable fun Card(enabled: Boolean = true, content: @Composable () -> Unit) {}
            @Composable fun f() { Card { } }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.argumentCount" }, "a trailing lambda on a @Composable functional last param must not be flagged; got $d")
    }

    // ---- assignment type mismatch ----

    @Test
    fun assignmentTypeMismatchIsFlagged() {
        val d = diagnose("package p\nfun f() {\n var n: Int = 0\n n = \"s\"\n}")
        assertTrue(
            d.any { it.code == "kt.typeMismatch" && it.message.contains("String") && it.message.contains("Int") },
            "n = \"s\" for a `var n: Int` must be flagged; got $d",
        )
    }

    @Test
    fun validAssignmentIsNotFlagged() {
        val d = diagnose("package p\nfun f() {\n var n: Int = 0\n n = 5\n}")
        assertTrue(d.none { it.code == "kt.typeMismatch" }, "n = 5 for a `var n: Int` must NOT be flagged; got $d")
    }

    // ---- when exhaustiveness ----

    @Test
    fun nonExhaustiveEnumWhenExpressionIsFlagged() {
        val d = diagnose(
            """
            package p
            enum class E { A, B, C }
            fun f(e: E): Int = when (e) {
                E.A -> 1
                E.B -> 2
            }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == "kt.whenExhaustive" && it.message.contains("C") },
            "a `when` expression over E missing the C branch must be flagged; got $d",
        )
    }

    @Test
    fun exhaustiveEnumWhenIsNotFlagged() {
        val d = diagnose(
            """
            package p
            enum class E { A, B }
            fun f(e: E): Int = when (e) {
                E.A -> 1
                E.B -> 2
            }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.whenExhaustive" }, "an exhaustive `when` must NOT be flagged; got $d")
    }

    @Test
    fun whenStatementIsNotFlagged() {
        // Statement position (its value is unused) carries no exhaustiveness requirement.
        val d = diagnose(
            """
            package p
            enum class E { A, B }
            fun f(e: E) {
                when (e) { E.A -> println("a") }
            }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.whenExhaustive" }, "a `when` STATEMENT must NOT be flagged; got $d")
    }

    @Test
    fun nonExhaustiveSealedWhenIsFlagged() {
        val d = diagnose(
            """
            package p
            sealed class S
            class Loading : S()
            class Done : S()
            fun f(s: S): Int = when (s) {
                is Loading -> 1
            }
            """.trimIndent(),
        )
        assertTrue(
            d.any { it.code == "kt.whenExhaustive" && it.message.contains("Done") },
            "a `when` over a same-file sealed hierarchy missing `Done` must be flagged; got $d",
        )
    }

    @Test
    fun negatedIsPatternWhenBacksOff() {
        // `!is Loading` covers everything except Loading; the coverage logic can't reason about a negated
        // pattern, so the check must back off (this `when` is in fact exhaustive) rather than falsely flag.
        val d = diagnose(
            """
            package p
            sealed class S
            class Loading : S()
            class Done : S()
            fun f(s: S): Int = when (s) {
                !is Loading -> 0
                is Loading -> 1
            }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.whenExhaustive" }, "a negated `is` pattern must make the check back off; got $d")
    }

    @Test
    fun whenWithElseIsNotFlagged() {
        val d = diagnose(
            """
            package p
            enum class E { A, B, C }
            fun f(e: E): Int = when (e) {
                E.A -> 1
                else -> 0
            }
            """.trimIndent(),
        )
        assertTrue(d.none { it.code == "kt.whenExhaustive" }, "a `when` with an `else` is exhaustive; got $d")
    }

    // ---- not callable ----

    @Test
    fun invokingNonFunctionValueIsFlagged() {
        val d = diagnose("package p\nfun f() {\n val x = 5\n x()\n}")
        assertTrue(
            d.any { it.code == "kt.notCallable" },
            "invoking a non-function value `x()` (where `x: Int`) must be flagged; got $d",
        )
    }

    @Test
    fun invokingFunctionIsNotFlagged() {
        val d = diagnose("package p\nfun g() {}\nfun f() {\n g()\n}")
        assertTrue(d.none { it.code == "kt.notCallable" }, "invoking a real function must NOT be flagged; got $d")
    }

    @Test
    fun invokingFunctionalValueIsNotFlagged() {
        val d = diagnose("package p\nfun f() {\n val run: () -> Unit = {}\n run()\n}")
        assertTrue(d.none { it.code == "kt.notCallable" }, "invoking a function-typed value must NOT be flagged; got $d")
    }
}
