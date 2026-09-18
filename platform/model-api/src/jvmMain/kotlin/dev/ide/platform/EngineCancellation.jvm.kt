package dev.ide.platform

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

private val current = ThreadLocal<CancelToken?>()

internal actual fun currentCancelToken(): CancelToken? = current.get()

/**
 * Installed as a coroutine context element rather than a plain thread-local write: a lane block may suspend
 * mid-run and resume on a DIFFERENT worker thread, and the element re-installs and restores the token around
 * every suspension, so synchronous polls deep inside the block keep working wherever the coroutine lands.
 */
internal actual suspend fun <T> withCancelToken(token: CancelToken, block: suspend () -> T): T =
    withContext(current.asContextElement(token)) { block() }
