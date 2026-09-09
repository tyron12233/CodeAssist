package dev.ide.interp.compose

import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The preview reported `no method ScaleToBounds(0) on …SharedTransitionScope$ResizeMode$Companion` for
 * Jetsnack's `resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds()`. `ScaleToBounds` is a companion
 * "factory that mimics a constructor"; androidx RENAMED it to the camelCase `scaleToBounds()` (both the current
 * androidx build AND the IDE's bundled Compose declare only the lowercase form). A parse-only preview lowers the
 * SOURCE name verbatim, so it looks up `ScaleToBounds` and dispatch found no such method — the call also omits
 * both defaulted params, so it must route through the `scaleToBounds$default` synthetic.
 *
 * This drives a hand-built MEMBER call (name `ScaleToBounds`, no args) against the REAL bundled
 * `ResizeMode.Companion` through the production [ComposeDispatcher] — the same path the device hit. It can't be
 * reproduced from source here: the desktopTest classpath IS the bundled Compose, whose metadata only names
 * `scaleToBounds`, so the resolver rejects `ScaleToBounds` at lowering (candidates=0) before dispatch is reached.
 */
class ScaleToBoundsCasingTest {

    @Test
    fun pascalCaseFactoryBridgesToTheRenamedCamelCaseRuntimeViaDefaultSynthetic() {
        val resizeMode = Class.forName("androidx.compose.animation.SharedTransitionScope\$ResizeMode")
        val companion = resizeMode.getField("Companion").get(null)!!
        val companionCls = companion.javaClass

        // Guard: the fixture only tests the fix if the runtime genuinely lacks the PascalCase name and carries the
        // renamed camelCase factory + its defaulted-arg synthetic (else the old matcher would already resolve it).
        assertTrue(companionCls.methods.none { it.name == "ScaleToBounds" }, "runtime must not have the PascalCase name")
        assertTrue(companionCls.methods.any { it.name == "scaleToBounds" }, "runtime has the renamed camelCase factory")
        assertTrue(companionCls.methods.any { it.name == "scaleToBounds\$default" }, "…and its \$default synthetic")

        val callee = ResolvedCallable.Library(
            displayName = "ScaleToBounds",
            ownerFqn = "androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion",
            methodName = "ScaleToBounds",
            paramTypes = emptyList(), isStatic = false, isConstructor = false, isInline = false,
            descriptorPrecise = true,
        )
        val call = RNode.Call(
            callee, DispatchKind.MEMBER, receiver = null, args = emptyList(),
            callSiteKey = CallSiteKey(0), source = SourceSpan(0, 0),
        )
        val result = ComposeDispatcher().dispatch(call, receiver = companion, args = emptyList())
        assertTrue(
            result != null && resizeMode.isInstance(result),
            "ScaleToBounds() must build a ResizeMode via the renamed scaleToBounds\$default; was $result",
        )
    }
}
