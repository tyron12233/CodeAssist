package dev.ide.platform

import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.ThreadLocal

/**
 * Kotlin/Native has no `ThreadLocal` class: `@ThreadLocal` on an object is the mechanism, giving that object's
 * state a separate copy per thread.
 */
@ThreadLocal
private object Current {
    var token: CancelToken? = null
}

internal actual fun currentCancelToken(): CancelToken? = Current.token

/**
 * Set and restore, where the JVM uses a coroutine context element.
 *
 * The difference is real and worth stating rather than hiding: if [block] suspends and resumes on another
 * thread, the token does not follow it, so a poll after that point sees nothing and the pass runs to
 * completion instead of bailing. That costs latency, never correctness, and the engine lane that hops threads
 * (the K2 IPC) is JVM-only anyway.
 */
internal actual suspend fun <T> withCancelToken(token: CancelToken, block: suspend () -> T): T {
    val previous = Current.token
    Current.token = token
    try {
        return block()
    } finally {
        Current.token = previous
    }
}
