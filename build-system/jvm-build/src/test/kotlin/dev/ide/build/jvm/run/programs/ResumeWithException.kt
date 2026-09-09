package dev.ide.build.jvm.run.programs

import kotlinx.coroutines.runBlocking
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The reported program: an exception the program's own code declares, delivered by
 * `Continuation.resumeWithException` from inside `suspendCoroutine`, must be caught by the `try/catch` around
 * the suspension point.
 *
 * The resume is SYNCHRONOUS, so the stdlib's real `SafeContinuation.getOrThrow()` throws it straight back into
 * the interpreted frame — as the program class's PEER (`dev.ide.jvm.peers.…MyException_Peer3`), which is what
 * the `catch` used to fail to recognize (see `CoroutineRunTest`).
 */
class MyException : Throwable("just an exception")

private suspend fun resumeWithAnException() {
    try {
        suspendCoroutine<Unit> { continuation ->
            continuation.resumeWithException(MyException())
        }
    } catch (e: MyException) {
        println("Caught")
    }
}

fun main() {
    runBlocking {
        resumeWithAnException()
    }
}
