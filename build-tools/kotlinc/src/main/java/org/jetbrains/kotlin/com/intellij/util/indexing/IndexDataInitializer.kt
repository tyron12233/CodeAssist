package org.jetbrains.kotlin.com.intellij.util.indexing

import kotlinx.coroutines.*
import kotlinx.coroutines.NonCancellable.invokeOnCompletion
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

abstract class IndexDataInitializer<T> : Callable<T?> {
    override fun call(): T? {
        val log = Logger.getInstance(javaClass.name)
        val started = Instant.now()
        return try {
            val tasks = prepareTasks()
            runParallelTasks(tasks, true)
            val result = finish()
            val message = getInitializationFinishedMessage(result)
            log.info(
                "Index data initialization done: ${
                    Duration.between(started, Instant.now()).toMillis()
                } ms. " + message
            )
            result
        } catch (t: Throwable) {
            log.error("Index data initialization failed", t)
            throw t
        }
    }

    protected abstract fun getInitializationFinishedMessage(initializationResult: T): String

    protected abstract fun finish(): T

    protected abstract fun prepareTasks(): Collection<ThrowableRunnable<*>>

    companion object {
        private val LOG = Logger.getInstance(IndexDataInitializer::class.java)

        @OptIn(ExperimentalCoroutinesApi::class)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        @JvmStatic
        fun <T> submitGenesisTask(action: Callable<T>): Future<T> {
            val async = scope.async { action.call() }

            val future = CompletableFuture<T>()
            future.whenComplete { _, e ->
                async.cancel(e?.let {
                    it as? CancellationException ?: CancellationException(
                        "CompletableFuture was completed exceptionally",
                        it
                    )
                })
            }
            async.invokeOnCompletion {
                try {
                    future.complete(async.getCompleted())
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                }
            }
            return future
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        @JvmStatic
        fun runParallelTasks(tasks: Collection<ThrowableRunnable<*>>, checkAppDisposed: Boolean) {
            if (tasks.isEmpty()) {
                return
            }

            runBlocking(
                Dispatchers.IO.limitedParallelism(2) +
                        CoroutineName("Index Storage Lifecycle")
            ) {
                for (task in tasks) {
                    launch {
                        executeTask(task, checkAppDisposed)
                    }
                }
            }
        }

        private fun executeTask(callable: ThrowableRunnable<*>, checkAppDisposed: Boolean) {
            val app = ApplicationManager.getApplication()
            try {
                // To correctly apply file removals in indices shutdown hook we should process all initialization tasks
                // Todo: make processing removed files more robust because ignoring 'dispose in progress' delays application exit and
                // may cause memory leaks IDEA-183718, IDEA-169374,
                if (checkAppDisposed && (app.isDisposed /*|| app.isDisposeInProgress()*/)) {
                    return
                }
                callable.run()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                LOG.error(t)
            }
        }
    }
}