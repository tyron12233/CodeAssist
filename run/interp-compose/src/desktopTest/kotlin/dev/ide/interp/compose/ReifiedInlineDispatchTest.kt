package dev.ide.interp.compose

import dev.ide.interp.Interpreter
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.RParam
import dev.ide.lang.kotlin.interp.RTypeArg
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof of the GENERAL reified-inline mechanism through the whole preview stack: the interp-core
 * [Interpreter] dispatches a library reified inline (`filterIsInstanceTo`, deliberately NOT one of interp-core's
 * hand-written intrinsics), the reflective call hits Kotlin's reification-marker guard, and the interpreter
 * recovers by routing to the injected [VmLibraryExecutor], which runs the function INTERPRETED with the
 * call-site type argument substituted. No per-function intrinsic is involved — the same path serves any library
 * reified inline.
 */
class ReifiedInlineDispatchTest {

    private val span = SourceSpan(0, 0)

    /** `fun box(mixed, dest) = mixed.filterIsInstanceTo<String>(dest)` built directly as a ResolvedTree. */
    private fun boxFn(): ResolvedFunction {
        val mixed = SlotId(0)
        val dest = SlotId(1)
        val call = RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = "filterIsInstanceTo",
                ownerFqn = "kotlin.collections.CollectionsKt",
                methodName = "filterIsInstanceTo",
                paramTypes = listOf(null, null),
                isStatic = false,
                isConstructor = false,
                isInline = true,
                typeParameterNames = listOf("R"), // only the reified parameter matters to the marker
            ),
            dispatch = DispatchKind.EXTENSION,
            receiver = RNode.Name(Binding.Param(mixed, "mixed"), span),
            args = listOf(RArg(RNode.Name(Binding.Param(dest, "dest"), span))),
            callSiteKey = CallSiteKey(0),
            source = span,
            typeArguments = listOf(RTypeArg(fqn = "kotlin.String")),
        )
        return ResolvedFunction(
            name = "box",
            params = listOf(RParam(mixed, "mixed", null), RParam(dest, "dest", null)),
            body = RNode.Return(call, span),
            diagnostics = emptyList(),
        )
    }

    @Test fun libraryReifiedInlineRunsThroughTheVmExecutor() {
        val executor = VmLibraryExecutor(source = ClassBytesSource.fromClasspath())
        val interp = Interpreter(mapOf("box/2" to boxFn()), libraryFallback = executor)

        val mixed: List<Any> = listOf(1, "a", 2, "b", 3)
        val dest = ArrayList<String>()
        val result = interp.call(boxFn(), listOf(mixed, dest))

        assertEquals(listOf("a", "b"), dest, "the reified inline filtered the strings into the destination")
        assertEquals(dest, result, "filterIsInstanceTo returns its destination")
    }
}
