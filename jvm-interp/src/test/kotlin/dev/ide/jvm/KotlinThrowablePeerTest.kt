package dev.ide.jvm

import dev.ide.jvm.kfixtures.rethrownByRealCode
import dev.ide.jvm.kfixtures.rethrownByRealCodeCaughtAsThrowable
import dev.ide.jvm.kfixtures.rethrownByRealCodeNotAnException
import dev.ide.jvm.kfixtures.suspendCoroutineResumeWithException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An exception an interpreted class declares over a real supertype crosses the bridge as a generated PEER, so
 * real code that throws it back at the interpreted frame delivers a real throwable whose class name is the
 * peer's (`dev.ide.jvm.peers.…_Peer3`), not the interpreted one. Matching a `catch` against that name finds
 * nothing and the exception escapes the `try` that should have caught it. Each case is checked against the
 * same construct compiled and run for real.
 */
class KotlinThrowablePeerTest {

    private val vm = Vm()
    private val KFX = "dev/ide/jvm/kfixtures/KFxKt"

    private fun call(name: String): Any? =
        vm.invokeStatic(KFX, name, "()Ljava/lang/String;", emptyList())

    @Test fun realCodeRethrowingAnInterpretedExceptionIsCaughtByItsInterpretedType() {
        assertEquals(rethrownByRealCode(), call("rethrownByRealCode"))
    }

    @Test fun realCodeRethrowingAnInterpretedExceptionIsCaughtByARealSupertype() {
        assertEquals(
            rethrownByRealCodeCaughtAsThrowable(),
            call("rethrownByRealCodeCaughtAsThrowable")
        )
    }

    @Test fun aCatchOfARealTypeTheInterpretedExceptionDoesNotExtendDoesNotMatch() {
        assertEquals(
            rethrownByRealCodeNotAnException(),
            call("rethrownByRealCodeNotAnException")
        )
    }

    /**
     * The reported failure: `try { suspendCoroutine { it.resumeWithException(MyException()) } } catch (e:
     * MyException)` printed nothing and the exception killed the run, because the synchronous resume makes the
     * stdlib's real `SafeContinuation.getOrThrow()` throw the peer back into the interpreted frame.
     */
    @Test fun suspendCoroutineResumedWithAnInterpretedExceptionIsCaught() {
        assertEquals(
            suspendCoroutineResumeWithException(),
            call("suspendCoroutineResumeWithException")
        )
        assertEquals("Caught just an exception", suspendCoroutineResumeWithException(), "oracle")
    }
}
