package dev.ide.ios

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCondition
import platform.Foundation.NSThread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one thread preview renders run on, with a stack big enough for them.
 *
 * The VM interprets by recursing on the host stack, one Kotlin frame per interpreted call, and Compose's
 * layout goes deep. A coroutine's worker thread on iOS has half a megabyte, which a preview overruns as a
 * crash rather than an exception; this thread has [STACK_BYTES]. One thread, because the VM is
 * single-threaded and every render shares one.
 */
internal object IosPreviewThread {
    private const val STACK_BYTES: ULong = 67_108_864u // 64 MB

    private val lock = NSCondition()
    private val jobs = ArrayDeque<() -> Unit>()
    private var thread: NSThread? = null

    /** Runs [block] on the preview thread and returns its result to the caller's coroutine. */
    suspend fun <T> run(block: () -> T): T = suspendCancellableCoroutine { cont ->
        post {
            val result = runCatching(block)
            result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }
    }

    /** Queues [job] on the preview thread without waiting for it; jobs run in the order they are posted. */
    fun post(job: () -> Unit) {
        lock.lock()
        try {
            jobs.addLast(job)
            if (thread == null) {
                thread = NSThread { loop() }.also {
                    it.stackSize = STACK_BYTES
                    it.name = "compose-preview"
                    it.start()
                }
            }
            lock.signal()
        } finally {
            lock.unlock()
        }
    }

    private fun loop() {
        while (true) {
            lock.lock()
            val job = try {
                while (jobs.isEmpty()) lock.wait()
                jobs.removeFirst()
            } finally {
                lock.unlock()
            }
            job()
        }
    }
}

/** The preview runtime the app bundle carries: the interpreter's JVM jars, under `preview-runtime/`. */
internal object IosPreviewRuntime {
    fun jars(): List<String> {
        val resources = platform.Foundation.NSBundle.mainBundle.resourcePath ?: return emptyList()
        val dir = IosFiles.join(resources, "preview-runtime")
        if (!IosFiles.isDirectory(dir)) return emptyList()
        return IosFiles.list(dir).filter { it.endsWith(".jar") }.sorted().map { IosFiles.join(dir, it) }
    }
}
