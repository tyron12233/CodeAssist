package dev.ide.interp.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.ide.interp.ReflectiveDispatcher
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the reported preview crash `no static background(2) on androidx.compose.foundation.BackgroundKt`
 * (`Modifier.background(color)` where `color` came from `animateColorAsState` / a `State<Color>.value` read).
 * Against the REAL foundation `background(color: Color, shape: Shape = RectangleShape)`: its value-class param
 * mangles the JVM name to `background-<hash>` and unboxes the param to `long`, and `shape` is defaulted — so a
 * receiver+color call (arity 2) has no exact-arity match and must route through the `background-<hash>$default`
 * synthetic. A `Color(…)` LITERAL is the UNBOXED `long` and always worked; a BOXED `Color` (as an `Object`-typed
 * state getter hands back) hit the synthetic's fit check, which the plain `isInstance` test rejected. Complements
 * the fixture-level [dev.ide.interp.ReflectiveDispatcherTest.boxedValueClassArgFitsADefaultedExtensionSyntheticParam].
 */
class BackgroundColorArgTest {

    @Test
    fun modifierBackgroundAcceptsABoxedColorThroughTheDefaultSynthetic() {
        val span = SourceSpan(0, 0)
        val callee = ResolvedCallable.Library(
            displayName = "background", ownerFqn = "androidx.compose.foundation.BackgroundKt",
            methodName = "background", paramTypes = emptyList(),
            isStatic = true, isConstructor = false, isInline = false, isComposable = false, descriptorPrecise = true,
        )
        // Assigning the value class to an `Any?` BOXES it — exactly what a `State<Color>.value` read yields (its
        // JVM getter returns `Object`), unlike a `Color(…)` literal which stays the unboxed `long`.
        val boxedColor: Any? = Color(0xFF6750A4)
        val call = RNode.Call(
            callee, DispatchKind.EXTENSION, receiver = RNode.Const(Modifier, null, span),
            args = listOf(RArg(RNode.Const(boxedColor, null, span))),
            callSiteKey = CallSiteKey(1), source = span,
        )
        val result = ReflectiveDispatcher().dispatch(call, receiver = Modifier, args = listOf(boxedColor))
        assertTrue(result is Modifier, "Modifier.background(<boxed Color>) must resolve via background-<hash>\$default to a Modifier, not throw `no static background(2)`")
    }
}
