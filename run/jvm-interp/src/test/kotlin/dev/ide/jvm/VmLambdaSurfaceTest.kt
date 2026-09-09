package dev.ide.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * When platform code invokes an interpreted lambda across the bridge and the lambda body throws, the boundary
 * ([VmLambda.invokeSamReal]) must surface the REAL throwable, never the interpreter's internal [VmException]
 * carrier — which has no message or stack, so a leak reads as a bare `dev.ide.jvm.VmException` FATAL on the
 * caller's thread (the reported Compose-preview `TextField` crash: an interpreted measure/effect lambda threw
 * and the opaque carrier escaped to the main thread).
 */
class VmLambdaSurfaceTest {

    private fun lambdaThrowing(t: Throwable): VmLambda = VmLambda(
        interfaceType = "java/lang/Runnable",
        samName = "run",
        samDescriptor = "()V",
        impl = MethodHandleRef(0, "x", "run", "()V", false),
        captured = emptyList(),
        invoker = SamInvoker { _, _ -> throw VmException(t) },
    )

    @Test
    fun interpretedLambdaExceptionSurfacesAsTheRealThrowable() {
        val boom = IllegalStateException("boom from interpreted lambda")
        val thrown = assertFailsWith<IllegalStateException> { lambdaThrowing(boom).invokeSamReal(emptyList()) }
        assertEquals("boom from interpreted lambda", thrown.message, "the real exception (with its message) must surface, not the opaque VmException")
    }

    @Test
    fun aProxyFallbackSuppliesTheReturnValueWhenAProxiedLambdaFails() {
        // Each `map` lambda throws; the fallback returns 7, so the real IntStream.map/sum sees 7 per element
        // instead of the failure (the Compose analog: a failed measure lambda returns an empty MeasureResult
        // rather than a null that NPEs the layout pass).
        val vm = Vm(bridge = ReflectiveBridge(proxyFallback = { _, _, _ -> 7 }))
        val sum = vm.invokeStatic("dev/ide/jvm/fixtures/Lambdas", "mapThrowing", "(I)I", listOf(3))
        assertEquals(21, sum, "the fallback value (7) is used per element, so 3 elements sum to 21")
    }

    @Test
    fun aCheckedExceptionFromAProxiedLambdaReachesTheCallerAsItself() {
        // Platform code invokes an interpreted lambda through a java.lang.reflect.Proxy, whose generated method
        // wraps any CHECKED throwable the interface does not declare (Consumer.accept declares none) in an
        // UndeclaredThrowableException. Interpreted Kotlin declares nothing, so the InterruptedException a
        // `Thread.sleep` inside a lambda throws must still reach the interpreted caller as itself. Otherwise no
        // `catch (e: InterruptedException)` of it can match, and on a thread the program started it surfaces as
        // an unhandled UndeclaredThrowableException (reported: the teardown interrupt of a program's sleeping
        // thread took the IDE's build process down with it).
        val boom = InterruptedException("interrupted inside the lambda")
        val lambda = VmLambda(
            interfaceType = "java/util/function/Consumer", samName = "accept",
            samDescriptor = "(Ljava/lang/Object;)V",
            impl = MethodHandleRef(0, "x", "accept", "(Ljava/lang/Object;)V", false), captured = emptyList(),
            invoker = SamInvoker { _, _ -> throw VmException(boom) },
        )
        val thrown = assertFailsWith<VmException> {
            ReflectiveBridge().invokeVirtual(
                arrayListOf("a"), "forEach", "(Ljava/util/function/Consumer;)V", listOf(lambda),
            )
        }
        assertSame(boom, thrown.value, "the caller must see what the lambda threw, not the proxy's wrapper")
    }

    @Test
    fun aBareInterpretedThrowValueSurfacesWithAMessageNotAnOpaqueCarrier() {
        // An interpreted object that isn't a real Throwable (a VmObject exception) still must not leak the raw
        // VmException; it surfaces as a described RuntimeException.
        val lambda = VmLambda(
            interfaceType = "java/lang/Runnable", samName = "run", samDescriptor = "()V",
            impl = MethodHandleRef(0, "x", "run", "()V", false), captured = emptyList(),
            invoker = SamInvoker { _, _ -> throw VmException("interpreted-only value") },
        )
        val thrown = assertFailsWith<RuntimeException> { lambda.invokeSamReal(emptyList()) }
        assertEquals(false, thrown is VmException, "must not be the opaque internal carrier")
        assertEquals(true, thrown.message?.contains("interpreted-only value") == true)
    }
}
