package dev.ide.interp

import dev.ide.lang.kotlin.interp.Binding
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Static-member access through a class qualifier — `System.out`, `System.currentTimeMillis()`,
 * `Integer.MAX_VALUE`/`Integer.parseInt(…)`. The resolver lowers the bare type name (`System`) to a
 * [Binding.ObjectRef]; a Java static-holder class has no `INSTANCE`/`Companion`, so it used to throw
 * "`java.lang.System` has no object/companion instance" the moment that receiver was evaluated. The
 * interpreter now recognizes a singleton-less class qualifier and reads/invokes its STATIC members off the
 * class itself, while a real Kotlin `object`/companion receiver still goes through its instance.
 */
class StaticMemberAccessTest {

    private val span = SourceSpan(0, 0)
    private fun run(body: RNode): Any? =
        Interpreter(emptyMap()).call(ResolvedFunction("f", emptyList(), RNode.Block(listOf(body), false, span), emptyList()), emptyList())

    private fun objRef(fqn: String, name: String) = RNode.Name(Binding.ObjectRef(fqn, name), span)
    private fun arg(value: Any?) = RArg(RNode.Const(value, null, span))

    @Test
    fun staticFieldReadThroughClassQualifier() {
        // `System.out` — a `public static final PrintStream out` field, not a getter.
        val get = RNode.PropertyGet(objRef("java.lang.System", "System"), Binding.Property("out", null, backingField = false), span)
        assertSame(System.out, run(get), "`System.out` must read the static field, not materialize an instance")
    }

    @Test
    fun staticConstantFieldReadThroughClassQualifier() {
        val get = RNode.PropertyGet(objRef("java.lang.Integer", "Integer"), Binding.Property("MAX_VALUE", null, backingField = false), span)
        assertEquals(Int.MAX_VALUE, run(get), "`Integer.MAX_VALUE` should read the static constant")
    }

    @Test
    fun staticNoArgMethodThroughClassQualifier() {
        // `System.currentTimeMillis()` — the resolver emits a MEMBER dispatch with the class qualifier as the
        // receiver; the interpreter re-routes a static callee to a static invocation (no instance).
        val callee = ResolvedCallable.Library(
            displayName = "currentTimeMillis", ownerFqn = "java.lang.System", methodName = "currentTimeMillis",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, descriptorPrecise = true,
        )
        val before = System.currentTimeMillis()
        val call = RNode.Call(callee, DispatchKind.MEMBER, receiver = objRef("java.lang.System", "System"), args = emptyList(), callSiteKey = CallSiteKey(0), source = span)
        val result = run(call)
        assertEquals(true, result is Long && result >= before, "`System.currentTimeMillis()` should return a plausible epoch millis; got $result")
    }

    @Test
    fun staticMethodWithArgThroughClassQualifier() {
        val callee = ResolvedCallable.Library(
            displayName = "parseInt", ownerFqn = "java.lang.Integer", methodName = "parseInt",
            paramTypes = emptyList(), isStatic = true, isConstructor = false, isInline = false, descriptorPrecise = true,
        )
        val call = RNode.Call(callee, DispatchKind.MEMBER, receiver = objRef("java.lang.Integer", "Integer"), args = listOf(arg("42")), callSiteKey = CallSiteKey(0), source = span)
        assertEquals(42, run(call), "`Integer.parseInt(\"42\")` should invoke the static overload")
    }

    @Test
    fun kotlinObjectReceiverStillGoesThroughItsInstance() {
        // Regression: a real `object` (with an `INSTANCE`) must NOT be mistaken for a static holder — its
        // member reads through the singleton instance, exactly as before.
        val get = RNode.PropertyGet(objRef("dev.ide.interp.Vault", "Vault"), Binding.Property("balance", null, backingField = false), span)
        assertEquals(100, run(get), "a Kotlin object member must still resolve via its INSTANCE")
    }

    @Test
    fun nestedObjectReadThroughEnclosingObject() {
        // `Icons.AutoMirrored` shape: the enclosing `object` resolves to its INSTANCE, then the nested object
        // name has no getter — it compiles to `<Enclosing>$<name>.INSTANCE`. The interpreter must materialize
        // the nested object's singleton, not throw "no readable property".
        val get = RNode.PropertyGet(objRef("dev.ide.interp.IconsLike", "IconsLike"), Binding.Property("AutoMirrored", null, backingField = false), span)
        assertSame(IconsLike.AutoMirrored, run(get), "`IconsLike.AutoMirrored` must resolve to the nested object's INSTANCE")
    }

    @Test
    fun deeplyNestedObjectReadThroughInstance() {
        // `Icons.AutoMirrored.Filled` — reading a nested object off another nested object's instance.
        val autoMirrored = RNode.PropertyGet(objRef("dev.ide.interp.IconsLike", "IconsLike"), Binding.Property("AutoMirrored", null, backingField = false), span)
        val get = RNode.PropertyGet(autoMirrored, Binding.Property("Filled", null, backingField = false), span)
        assertSame(IconsLike.AutoMirrored.Filled, run(get), "a nested object off a nested object's instance must resolve")
    }
}

/** Mirrors the `androidx.compose.material.icons.Icons` shape: an `object` with nested `object`s reached as
 *  `IconsLike.AutoMirrored` / `IconsLike.AutoMirrored.Filled` (each compiles to `<Enclosing>$<name>.INSTANCE`,
 *  not a getter on the enclosing instance). */
object IconsLike {
    object AutoMirrored {
        object Filled
    }
}
