package dev.ide.platform.impl

import dev.ide.platform.Activity
import dev.ide.platform.ActivityManager
import dev.ide.platform.ActivityScope
import dev.ide.platform.ActivitySpec
import dev.ide.platform.ProgressReporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext

/**
 * Device-aware coroutine dispatchers. [cpu] is a bounded pool sized to the device's cores for CPU
 * work (compile/dex/parse); [io] is a separate elastic pool for file/network work, so a burst of IO
 * never starves CPU tasks.
 */
class PlatformDispatchers(
    parallelism: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
) {
    val cpu: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(parallelism)
    val io: CoroutineDispatcher = Dispatchers.IO
}

/**
 * The general background-work scheduler. Each [launch] runs its block on the [PlatformDispatchers.cpu]
 * pool as a child of [scope], so disposing the platform cancels every in-flight activity (structured
 * concurrency). The block receives an [ActivityScope] that ties it to the shared model lock and to
 * cooperative cancellation.
 *
 * This is distinct from the build task engine (build-api): a build is *one* activity that internally
 * drives a task graph.
 */
class ActivityManagerImpl(
    private val scope: CoroutineScope,
    private val lock: ModelReadWriteLock,
    private val dispatchers: PlatformDispatchers = PlatformDispatchers(),
    /** Sink for progress updates (wire to the UI). Receives (spec, fraction, message). */
    private val onProgress: (ActivitySpec, Double, String?) -> Unit = { _, _, _ -> },
) : ActivityManager {

    override fun <T> launch(spec: ActivitySpec, block: suspend ActivityScope.() -> T): Activity<T> {
        val deferred: Deferred<T> = scope.async(dispatchers.cpu + CoroutineName(spec.title)) {
            val ctx = coroutineContext
            val progress = ProgressReporterImpl(spec, ctx, onProgress)
            block(ActivityScopeImpl(ctx, progress, lock))
        }
        return ActivityImpl(spec, deferred)
    }
}

private class ActivityImpl<T>(
    override val spec: ActivitySpec,
    private val deferred: Deferred<T>,
) : Activity<T> {
    override suspend fun await(): T = deferred.await()
    override fun cancel() = deferred.cancel()
    override val isActive: Boolean get() = deferred.isActive
}

private class ActivityScopeImpl(
    private val context: CoroutineContext,
    override val progress: ProgressReporter,
    private val lock: ModelReadWriteLock,
) : ActivityScope {

    override fun checkCanceled() = context.ensureActive()

    override suspend fun <T> readAction(block: () -> T): T {
        context.ensureActive()
        return lock.read(block)
    }

    override suspend fun <T> writeAction(block: () -> T): T {
        context.ensureActive()
        return lock.write(block)
    }
}

private class ProgressReporterImpl(
    private val spec: ActivitySpec,
    private val context: CoroutineContext,
    private val onProgress: (ActivitySpec, Double, String?) -> Unit,
) : ProgressReporter {

    override fun report(fraction: Double, message: String?) = onProgress(spec, fraction, message)

    override fun checkCanceled() = context.ensureActive()

    override val isCanceled: Boolean get() = !context.isActive
}
