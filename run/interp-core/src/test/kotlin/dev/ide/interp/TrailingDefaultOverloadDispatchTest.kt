package dev.ide.interp

import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reported `Modifier.padding(start = 16.dp)` bug: it padded every direction. Named-argument reordering
 * TRIMS the trailing omitted slots, so the four-way overload's call reached the dispatcher as a single value —
 * the same shape as a call to the one-parameter `padding(all)` sibling, which the exact-arity reflective lookup
 * then bound. Static resolution had already picked the right overload, so dispatch must honour its declared
 * parameter count (as the constructor and `@Composable` paths do) instead of re-deriving the overload from the
 * argument count. [pad]'s three overloads mirror `Modifier.padding`'s.
 */
class TrailingDefaultOverloadDispatchTest {

    private val span = SourceSpan(0, 0)
    private val facade = "dev.ide.interp.TrailingDefaultOverloadDispatchTestKt"

    @Test
    fun aLeadingNamedArgumentKeepsTheOverloadResolutionPicked() {
        assertEquals(
            "sides:5,0,0,0",
            call("pad", listOf("start", "top", "end", "bottom"), RArg(const(5), name = "start")),
            "`pad(start = 5)` must reach the four-way overload, not the one-parameter `all` sibling",
        )
    }

    @Test
    fun aShorterOverloadIsReachedByItsOwnLeadingName() {
        assertEquals(
            "hv:7,0",
            call("pad", listOf("horizontal", "vertical"), RArg(const(7), name = "horizontal")),
            "`pad(horizontal = 7)` must reach the two-way overload — it trims to the same single-argument shape",
        )
    }

    @Test
    fun aPositionalCallStillReachesTheOneParameterOverload() {
        assertEquals(
            "all:3",
            call("pad", listOf("all"), RArg(const(3))),
            "a positional `pad(3)` still resolves to the one-parameter overload",
        )
    }

    @Test
    fun aRuntimeShorterThanTheResolvedOverloadStillBinds() {
        // A version-skewed library: the index recorded three parameters, the jar on the classpath declares one
        // and has no `$default` synthetic. The exact-arity match is the fallback, so the call still binds.
        assertEquals(
            "one:4",
            call("skewed", listOf("a", "b", "c"), RArg(const(4))),
            "with no fitting \$default synthetic the exact-arity match must still be taken",
        )
    }

    private fun const(v: Any?) = RNode.Const(v, null, span)

    /** Interpret a top-level call to [name] whose RESOLVED overload declares [paramNames], with [args]. */
    private fun call(name: String, paramNames: List<String>, vararg args: RArg): Any? {
        val callee = ResolvedCallable.Library(
            displayName = name, ownerFqn = facade, methodName = name,
            paramTypes = List(paramNames.size) { null },
            isStatic = true, isConstructor = false, isInline = false, paramNames = paramNames,
        )
        val call = RNode.Call(
            callee, DispatchKind.TOP_LEVEL, receiver = null,
            args = args.toList(), callSiteKey = CallSiteKey(1), source = span,
        )
        return Interpreter(emptyMap()).call(ResolvedFunction("f", emptyList(), call, emptyList()), emptyList())
    }
}

/** `Modifier.padding(all)` — one parameter, no defaults, so no `$default` synthetic. */
fun pad(all: Int): String = "all:$all"

/** `Modifier.padding(horizontal, vertical)` — every parameter defaulted. */
fun pad(horizontal: Int = 0, vertical: Int = 0): String = "hv:$horizontal,$vertical"

/** `Modifier.padding(start, top, end, bottom)` — every parameter defaulted. */
fun pad(start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0): String = "sides:$start,$top,$end,$bottom"

/** A function the resolver believes takes three parameters and the runtime declares with one. */
fun skewed(a: Int): String = "one:$a"
