package kotlinx.coroutines

import kotlin.coroutines.*

typealias CancellationException = java.util.concurrent.CancellationException
object NonCancellable : AbstractCoroutineContextElement(Key) { object Key : CoroutineContext.Key<NonCancellable> }
object Dispatchers { val IO: CoroutineContext = EmptyCoroutineContext }
fun CoroutineContext.ensureActive() {}
suspend fun <T> withContext(ctx: CoroutineContext, block: suspend () -> T): T = block()
fun <T> runBlocking(block: suspend () -> T): T {
    var res: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { res = result }
    })
    return res!!.getOrThrow()
}
