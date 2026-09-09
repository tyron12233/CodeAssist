package dev.ide.testkit

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Drive a `suspend` [block] to completion synchronously on the calling thread (no dispatcher), returning its
 * result or rethrowing its failure. For tests that call a `suspend` analyzer entry point without pulling in
 * the coroutines runtime. (Where a test already uses kotlinx-coroutines, `runBlocking` is equivalent.)
 */
fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
