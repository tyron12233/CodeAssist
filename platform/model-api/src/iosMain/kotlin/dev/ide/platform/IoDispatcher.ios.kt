package dev.ide.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_QUEUE_CONCURRENT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_attr_make_with_qos_class
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.posix.QOS_CLASS_UTILITY

/**
 * Where iOS runs blocking work: a GCD concurrent queue, at utility priority.
 *
 * `Dispatchers.IO` does not exist on Kotlin/Native — coroutines declares it internal there — and the
 * obvious substitute, `Dispatchers.Default`, is the wrong one the moment a host actually blocks. Default
 * is the CPU pool: its width is the core count, and a socket waiting on a reply occupies one of those
 * slots doing nothing. With a real backend behind the store that is several at once (the feed, the
 * profile, one per media lane), and everything else dispatched there — image decodes, file reads, the
 * tree build — queues behind them. What that looks like on a phone is an app that stutters while the
 * store loads.
 *
 * GCD is the right primitive for this on Apple platforms: it grows the pool when its workers block, which
 * is exactly the behaviour `Dispatchers.IO` provides on the JVM, and `QOS_CLASS_UTILITY` tells the system
 * this work must never be scheduled ahead of the main thread's rendering.
 */
@OptIn(ExperimentalForeignApi::class)
private val ioQueue: dispatch_queue_t = dispatch_queue_create(
    "dev.ide.io",
    dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_CONCURRENT, QOS_CLASS_UTILITY, 0),
)

/**
 * The single IO dispatcher for the whole iOS app.
 *
 * Declared in the lowest module rather than in any one that needs it, because several do: the UI's file
 * reads (:ide-ui-core), the store's requests (:store-bridge) and the dependency resolver's downloads
 * (:deps-impl) are the same pool on this platform, and separate ones would defeat the point of having one
 * that is sized for blocking.
 */
@OptIn(ExperimentalForeignApi::class)
actual val ioDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(ioQueue) { block.run() }
    }
}
