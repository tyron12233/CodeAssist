package dev.ide.interp.compose

import dev.ide.interp.InterpretedLambda
import dev.ide.interp.OmittedArg
import dev.ide.interp.ReflectiveDispatcher
import dev.ide.jvm.ClassBytesSource
import dev.ide.lang.kotlin.interp.CallSiteKey
import dev.ide.lang.kotlin.interp.DispatchKind
import dev.ide.lang.kotlin.interp.RArg
import dev.ide.lang.kotlin.interp.RNode
import dev.ide.lang.kotlin.interp.ResolvedCallable
import dev.ide.lang.kotlin.interp.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The VM-backed library executor: dependency classes that only exist as class bytes (here: the libfixture
 * package, forced "not host-loadable" through the test seam) run interpreted, bridging to real host classes
 * exactly as the on-device preview bridges to the IDE's bundled runtime.
 */
class VmLibraryExecutorTest {

    private val pkg = "dev.ide.interp.compose.libfixture"
    private val facade = "$pkg.LibFixtureKt"

    private fun executor() = VmLibraryExecutor(
        hostLoadable = { !it.startsWith(pkg) },
        source = ClassBytesSource.fromClasspath(),
    )

    @Test fun staticCallConstructsALibraryObjectAndMembersDispatchOnIt() {
        val x = executor()
        val counter = x.invokeStatic(facade, "makeCounter", listOf(7))!!
        assertTrue(x.ownsInstance(counter), "a library instance surfaces as a VM-owned peer")
        assertEquals(8, x.invokeInstance(counter, "add", listOf(1)))
        assertEquals(8, x.propertyOrNull(counter, "value")!!.value)
    }

    @Test fun constructorAndMethodDefaultsFillThroughTheSynthetics() {
        val x = executor()
        val c = x.construct("$pkg.Counter", emptyList())!! // start defaults to 10
        assertEquals(10, x.propertyOrNull(c, "value")!!.value)
        assertEquals("v10", x.invokeInstance(c, "label", listOf(OmittedArg)))
        assertEquals("p10", x.invokeInstance(c, "label", listOf("p")))
    }

    @Test fun objectSingletonPropertiesAndMethods() {
        val x = executor()
        val registry = x.objectInstance("$pkg.Registry")!!
        assertEquals("registry", x.propertyOrNull(registry, "name")!!.value)
        val counter = x.invokeStatic(facade, "makeCounter", listOf(3))!!
        assertEquals("reg:3", x.invokeInstance(registry, "describe", listOf(counter)))
    }

    @Test fun varargsPackIntoTheRealArray() {
        val x = executor()
        assertEquals(6, x.invokeStatic(facade, "sum", listOf(1, 2, 3)))
        assertEquals(0, x.invokeStatic(facade, "sum", emptyList()))
    }

    @Test fun interpretedLambdaIsProxiedIntoTheLibraryCall() {
        val x = executor()
        val lambda = object : InterpretedLambda {
            override val paramCount: Int = 1
            override fun invoke(args: List<Any?>): Any? = (args[0] as Int) * 2
        }
        assertEquals(6, x.invokeStatic(facade, "apply3", listOf(lambda)))
    }

    @Test fun extensionReceiverRidesAsTheLeadingArgument() {
        val x = executor()
        val counter = x.invokeStatic(facade, "makeCounter", listOf(10))!!
        assertEquals(20, x.invokeStatic(facade, "doubled", listOf(counter), leadingReceivers = 1))
    }

    @Test fun libraryCodeReturnsRealBridgedValues() {
        val x = executor()
        val sb = x.invokeStatic(facade, "buildText", emptyList())
        assertTrue(sb is StringBuilder, "a host-class value built inside the VM comes back real, not as a peer")
        assertEquals("lib", sb.toString())
    }

    @Test fun dispatcherRoutesAnUnloadableOwnerToTheExecutor() {
        // The fixture IS on the test classpath, so the dispatcher must be given a loader that cannot see it —
        // the on-device shape, where a dependency class exists only in the project's jars.
        val filteringLoader = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name.startsWith(pkg)) throw ClassNotFoundException(name)
                else Class.forName(name, false, VmLibraryExecutorTest::class.java.classLoader)
        }
        val d = ReflectiveDispatcher(loader = filteringLoader, libraryFallback = executor())
        val span = SourceSpan(0, 0)
        val call = RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = "makeCounter", ownerFqn = facade, methodName = "makeCounter",
                paramTypes = listOf(null), isStatic = true, isConstructor = false, isInline = false,
            ),
            dispatch = DispatchKind.TOP_LEVEL,
            receiver = null,
            args = listOf(RArg(RNode.Const(5, null, span))),
            callSiteKey = CallSiteKey(0),
            source = span,
        )
        val counter = d.dispatch(call, receiver = null, args = listOf(5))!!
        // The peer flows back through the dispatcher's instance path on the next call.
        assertEquals(6, d.dispatch(memberCall("add"), counter, listOf(1)))
    }

    @Test fun projectPreferredNamespaceIsInterpretedEvenWhenHostLoadable() {
        // The version-skew shadow that fixes Material3 Expressive previews: even when the host CAN load the
        // class (hostLoadable = true — the IDE's own bundled Compose Material3), a project-preferred namespace
        // is interpreted from the project's OWN bytes, so the project's (newer) API surface renders instead of
        // the bundled build's. Here the libfixture package stands in for `androidx.compose.material3.`.
        val src = ClassBytesSource.fromClasspath()
        // Baseline: host-loadable + no matching preferred prefix (the production default is material3 only) →
        // bridged, not interpreted. This is the pre-fix behavior for the bundled runtime.
        val bridged = VmLibraryExecutor(hostLoadable = { true }, source = src)
        assertFalse(bridged.hasClass("$pkg.Registry"), "a host-loadable class outside a preferred prefix stays bridged")

        // Flip: mark the fixture namespace preferred and the same class is now interpreted DESPITE being
        // host-loadable, with its singleton the VM-owned interpreted instance (not the bundled one).
        val preferred = VmLibraryExecutor(hostLoadable = { true }, source = src, projectPreferredPrefixes = listOf("$pkg."))
        assertTrue(preferred.hasClass("$pkg.Registry"), "a preferred-namespace class is interpreted despite being host-loadable")
        val registry = preferred.objectInstance("$pkg.Registry")!!
        assertTrue(preferred.ownsInstance(registry), "its singleton is the VM-owned interpreted instance")
        assertEquals("registry", preferred.propertyOrNull(registry, "name")!!.value)
    }

    private fun memberCall(name: String): RNode.Call {
        val span = SourceSpan(0, 0)
        return RNode.Call(
            callee = ResolvedCallable.Library(
                displayName = name, ownerFqn = "$pkg.Counter", methodName = name,
                paramTypes = listOf(null), isStatic = false, isConstructor = false, isInline = false,
            ),
            dispatch = DispatchKind.MEMBER,
            receiver = null,
            args = listOf(RArg(RNode.Const(1, null, span))),
            callSiteKey = CallSiteKey(1),
            source = span,
        )
    }
}
