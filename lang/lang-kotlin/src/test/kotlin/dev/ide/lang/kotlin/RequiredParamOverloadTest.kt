package dev.ide.lang.kotlin

import dev.ide.lang.kotlin.interp.KotlinTreeResolver
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.walk
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Overload applicability: an overload with an UNBOUND, non-defaulted value parameter is not a candidate for a
 * call. This mirrors Material3's `Card`/`Button`, which have a plain overload and a CLICKABLE overload whose extra
 * leading `onClick` has NO default. A plain `Card { }` must pick the non-clickable overload — picking the
 * clickable one would leave its required `onClick` null, building a spuriously-clickable preview node that NPEs
 * (`Function0.invoke()` on null in `ClickableNode`) the moment the user taps it. The positional most-complete
 * tie-break used to prefer the LARGER (clickable) overload; it must now reject it as inapplicable.
 */
class RequiredParamOverloadTest {

    private fun calleeOf(code: String, callName: String): ResolvedCallable {
        val service = KotlinSymbolService(sourceRoots = emptyList(), classpathJars = listOf(stdlibJarPath()))
        val kt = KotlinParserHost.parse("Main.kt", code)
        val parsed = KotlinParsedFile(kt, FakeFile("Main.kt"), 0)
        val fn = assertNotNull(KotlinTreeResolver(kt, parsed, service).lowerFirstFunction())
        assertTrue(fn.isComplete, "`$callName` must lower cleanly; diags=${fn.diagnostics.map { it.reason }}")
        var found: ResolvedCallable? = null
        fn.body.walk { if (it is RNode.Call && it.callee.displayName == callName) found = it.callee }
        return assertNotNull(found, "the `$callName` call must lower to a Call")
    }

    @Test
    fun plainCallPicksTheOverloadWithoutTheRequiredLeadingLambda() {
        // `Card { }` supplies only the trailing content lambda. The clickable overload's leading `onClick` is
        // required (no default) → inapplicable; the plain overload (all non-supplied params defaulted) wins.
        val code = """
            package demo
            fun use() { Card { } }
            fun Card(modifier: Int = 0, content: () -> Unit) {}
            fun Card(onClick: () -> Unit, modifier: Int = 0, content: () -> Unit) {}
        """.trimIndent()
        val callee = calleeOf(code, "Card") as? ResolvedCallable.Source
        assertNotNull(callee, "a source Card overload should be chosen")
        assertEquals(
            "demo.Card/2", callee.declId,
            "a plain `Card { }` must resolve to the 2-param overload without the required onClick, not /3",
        )
        assertTrue("onClick" !in callee.paramNames, "the chosen overload must not carry a required onClick; got ${callee.paramNames}")
    }

    @Test
    fun providingTheRequiredLambdaStillPicksTheClickableOverload() {
        // Regression the other way: when `onClick` IS supplied, the clickable overload is applicable and chosen —
        // the fix rejects only overloads whose required param is UNBOUND, it doesn't blanket-prefer the smaller one.
        val code = """
            package demo
            fun use() { Card(onClick = { }) { } }
            fun Card(modifier: Int = 0, content: () -> Unit) {}
            fun Card(onClick: () -> Unit, modifier: Int = 0, content: () -> Unit) {}
        """.trimIndent()
        val callee = calleeOf(code, "Card") as? ResolvedCallable.Source
        assertNotNull(callee, "a source Card overload should be chosen")
        assertEquals(
            "demo.Card/3", callee.declId,
            "providing onClick makes the clickable 3-param overload applicable and most complete",
        )
        assertTrue("onClick" in callee.paramNames, "the clickable overload carries onClick; got ${callee.paramNames}")
    }
}
