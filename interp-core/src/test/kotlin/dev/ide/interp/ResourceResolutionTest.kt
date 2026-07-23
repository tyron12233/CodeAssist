package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The interpreter's `R.<type>.<name>` resolution hook. `R.string.app_name` lowers to a NESTED `PropertyGet`
 * whose receiver is the synthetic R chain (`PropertyGet(ObjectRef("<ns>.R"), "string")`) — which has no bytecode
 * to reflect, so evaluating it throws. The hook must resolve the resource id from the read's binding owner
 * (`<ns>.R.string`) via the injected [PreviewResourceResolver] BEFORE the receiver is ever evaluated.
 */
class ResourceResolutionTest {

    private val span = SourceSpan(0, 0)

    private val res = object : PreviewResourceResolver {
        override fun rClassField(ownerFqn: String, fieldName: String): Int? =
            if (ownerFqn == "com.example.R.string" && fieldName == "app_name") 0x7f0e0001 else null
        override fun string(id: Int): String? = null
        override fun stringArray(id: Int): List<String>? = null
        override fun plural(id: Int, quantity: Int): String? = null
        override fun color(id: Int): Any? = null
        override fun dimension(id: Int): Any? = null
        override fun painter(id: Int): Any? = null
    }

    @Test
    fun rStringReferenceResolvesToItsIdWithoutMaterializingTheSyntheticRClass() {
        // Build exactly what the lowerer emits for `R.string.app_name`.
        val inner = RNode.PropertyGet(
            RNode.Name(Binding.ObjectRef("com.example.R", "R"), span),
            Binding.Property("string", "com.example.R", backingField = false), span,
        )
        val outer = RNode.PropertyGet(inner, Binding.Property("app_name", "com.example.R.string", backingField = false), span)
        val fn = ResolvedFunction("f", emptyList(), outer, emptyList())
        // If the hook didn't fire first, evaluating `inner` (ObjectRef("com.example.R"), no runtime class) would
        // throw rather than returning the id.
        assertEquals(0x7f0e0001, Interpreter(emptyMap(), resources = res).call(fn, emptyList()))
    }

    @Test
    fun unknownResourceWithAResolverDegradesToZeroInsteadOfCrashing() {
        // With a resolver present, an `R.string.<name>` it can't map (unknown resource) degrades to the
        // unresolved id 0 — the downstream `stringResource(0)` is intercepted (→ empty) rather than crashing
        // the whole preview by materializing the bytecode-less synthetic `R`.
        val inner = RNode.PropertyGet(
            RNode.Name(Binding.ObjectRef("com.example.R", "R"), span),
            Binding.Property("string", "com.example.R", backingField = false), span,
        )
        val outer = RNode.PropertyGet(inner, Binding.Property("unknown_str", "com.example.R.string", backingField = false), span)
        val fn = ResolvedFunction("f", emptyList(), outer, emptyList())
        assertEquals(0, Interpreter(emptyMap(), resources = res).call(fn, emptyList()), "unknown resource → id 0, not a crash")
        // With NO resolver there is nothing to intercept `stringResource(0)` (it would reach the real call and
        // throw), so the read must NOT silently degrade — it surfaces the clear "no resolver" boundary instead.
        assertFailsWith<InterpreterException> { Interpreter(emptyMap()).call(fn, emptyList()) }
    }
}
