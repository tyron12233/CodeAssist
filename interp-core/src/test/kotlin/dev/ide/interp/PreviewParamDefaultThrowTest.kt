package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import dev.ide.lang.kotlin.symbols.KotlinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A `@Preview` entry `fun GreetingPreview(sdk: Int = Build.VERSION.SDK_INT)` whose parameter default reads an
 * Android-only static that isn't on the (desktop) classpath. The interpreter evaluates a parameter's default
 * eagerly when binding, so before the fix that eval threw ("cannot load android.os.Build") and aborted the whole
 * render — even though the body never reads `sdk`. In gap-tolerant mode (the preview path) a throwing default
 * must degrade to a type-appropriate zero and let the preview render; outside it (console Run/tests) the throw
 * still propagates so a broken program is reported.
 */
class PreviewParamDefaultThrowTest {

    private val span = SourceSpan(0, 0)

    /** `sdk: Int = Build.VERSION.SDK_INT` — a default that can't evaluate off-device. */
    private fun sdkFromBuildVersion() = RNode.PropertyGet(
        RNode.PropertyGet(
            RNode.Name(Binding.ObjectRef("android.os.Build", "Build"), span),
            Binding.Property("VERSION", "android.os.Build", backingField = false), span,
        ),
        Binding.Property("SDK_INT", "android.os.Build.VERSION", backingField = false), span,
    )

    /** `fun D(sdk: Int = <default>): <body>`. */
    private fun entry(default: RNode, body: RNode) = ResolvedFunction(
        "GreetingPreview",
        listOf(RParam(SlotId(0), "sdk", KotlinType("kotlin.Int"), default = default)),
        body, emptyList(),
    )

    @Test
    fun gapTolerantModeDegradesAThrowingParameterDefaultInsteadOfAbortingTheRender() {
        // Body ignores `sdk`: the render doesn't depend on the default at all.
        val fn = entry(sdkFromBuildVersion(), RNode.Const("rendered", null, span))
        val result = Interpreter(emptyMap(), tolerateGaps = true).call(fn, emptyList())
        assertEquals("rendered", result, "an unusable parameter default must not abort a gap-tolerant render")
    }

    @Test
    fun gapTolerantModeSubstitutesAZeroForAThrowingIntDefaultSoTheBodyCanReadIt() {
        // Body returns `sdk` — the degraded value must be the Int zero (not null), so a primitive read is safe.
        val fn = entry(sdkFromBuildVersion(), RNode.Name(Binding.Param(SlotId(0), "sdk"), span))
        val result = Interpreter(emptyMap(), tolerateGaps = true).call(fn, emptyList())
        assertEquals(0, result, "a throwing Int default degrades to 0 in gap-tolerant mode")
    }

    @Test
    fun withoutGapToleranceAThrowingDefaultStillPropagates() {
        // Console Run / tests: a genuinely broken program must still fail loudly, not silently render.
        val fn = entry(sdkFromBuildVersion(), RNode.Const("rendered", null, span))
        assertFailsWith<InterpreterException> { Interpreter(emptyMap(), tolerateGaps = false).call(fn, emptyList()) }
    }
}
