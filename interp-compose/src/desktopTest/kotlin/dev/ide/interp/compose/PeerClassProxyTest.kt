package dev.ide.interp.compose

import dev.ide.interp.ReflectiveDispatcher
import dev.ide.interp.SourceObject
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.ClassFlavor
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.SlotId
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An interpreted `object : SomeAbstractClass() { }` passed to library code that expects that CLASS. A
 * `java.lang.reflect.Proxy` can only implement interfaces, so this needs a real generated subclass —
 * [PeerClassProxyFactory] over jvm-interp's peer generator. Two levels: the factory generates a real subclass
 * whose override routes into the interpreter, and the reflective dispatcher wires a SourceObject arg through it.
 */
class PeerClassProxyTest {

    private val span = SourceSpan(0, 0)

    @Test
    fun factoryGeneratesARealSubclassRoutingTheOverrideIntoTheInvoker() {
        val peer = PeerClassProxyFactory().proxyOrNull(Any(), Greeter::class.java, emptyList(), setOf("greet")) { name, _ ->
            if (name == "greet") "OK" else null
        }
        assertEquals("OK", useGreeter(peer as Greeter), "the generated subclass routes greet() into the invoker")
    }

    @Test
    fun sourceObjectExtendingLibraryClassCrossesToLibraryCodeViaTheDispatcher() {
        // A SourceObject standing for `object : Greeter { override fun greet() = "OK" }`.
        val cls = ResolvedClass(
            fqn = "Anon", simpleName = "<anon>", flavor = ClassFlavor.CLASS,
            isData = false, isSealed = false, isAbstract = false,
            primaryParams = emptyList(), initSteps = emptyList(),
            methods = mapOf("greet/0" to ResolvedFunction("greet", emptyList(), RNode.Block(emptyList(), false, span), emptyList())),
            receiverSlot = SlotId(0), supertypes = listOf("dev.ide.interp.compose.Greeter"),
            enumEntries = emptyList(), diagnostics = emptyList(),
        )
        val obj = SourceObject(cls)
        obj.proxyInvoker = { name, _ -> if (name == "greet") "OK" else null }
        // useGreeter(g: Greeter) = g.greet() — a real compiled library function taking the abstract CLASS.
        val call = RNode.Call(
            ResolvedCallable.Library(
                "useGreeter", "dev.ide.interp.compose.PeerClassProxyTestKt", "useGreeter",
                listOf(null), isStatic = true, isConstructor = false, isInline = false,
            ),
            DispatchKind.TOP_LEVEL, receiver = null,
            args = listOf(RArg(RNode.Const(0, null, span))), callSiteKey = CallSiteKey(0), source = span,
        )
        val dispatcher = ReflectiveDispatcher(classProxies = PeerClassProxyFactory())
        assertEquals("OK", dispatcher.dispatch(call, null, listOf(obj)), "the SourceObject is realized as a Greeter subclass")
    }
}

abstract class Greeter {
    abstract fun greet(): String
}

fun useGreeter(g: Greeter): String = g.greet()
