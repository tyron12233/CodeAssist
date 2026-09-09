package dev.ide.android.fork

import android.content.Context
import dev.ide.android.R8ForkSupport
import dev.ide.lang.kotlin.compile.KotlinCompileRequest
import dev.ide.lang.kotlin.compile.KotlinCompileResult
import dev.ide.lang.kotlin.compile.KotlinCompilerBackend
import dev.ide.platform.log.Log
import dev.ide.platform.log.Logger
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Kotlin compiles in PERSISTENT forked command-line VMs instead of the app's own process.
 *
 * An app process's managed heap is capped at the device's `dalvik.vm.heapsize` (576MB on the measured
 * devices) no matter the physical RAM, and the Kotlin compiler shares that heap with the editor engine. A VM
 * launched from the command line is not a zygote app process, so its `-Xmx` can exceed the cap, which is the
 * same reason [dev.ide.android.ForkedR8Shrinker] forks R8.
 *
 * Unlike R8 the VM is not per invocation. R8's pass is one whole-program run with no reusable state, whereas
 * kotlinc's cost is dominated by state a fork throws away: class-loading the compiler, standing up
 * IntelliJ-core's application environment, and reading `android.jar`, the stdlib and every library jar, which
 * `KotlinEnvironmentKeepAlive` deliberately keeps warm across compiles. Measured on device, a fork per
 * compile costs several times a warm in-process compile and never improves. So a worker ([KotlincWorkerMain])
 * is started once and reused for the rest of the session, and the warm environment lives inside it.
 *
 * Everything degrades to [fallback], the in-process compiler, rather than failing a build: no launcher, a
 * device whose RAM cannot back a fork, a worker that will not start, a worker that dies mid-compile, or a
 * protocol error. After [MAX_WORKER_FAILURES] such failures the fork is abandoned for the rest of the
 * session, so a device where this cannot work pays the cost once rather than on every module.
 *
 * The forked VMs use the same launchers as the R8 fork, so a fork that aborts on startup is already
 * attributed to a tool VM rather than to the app by `dev.ide.platform.ForkedToolVm.isToolVmCrash`.
 */
class ForkedKotlinCompiler(
    context: Context,
    /** The "Kotlin compiler execution" setting: `forked` (default) or `inprocess`. Null/unknown → forked. */
    private val modeProvider: () -> String? = { null },
    /** The "Kotlin compiler VM heap" setting in MB, or null for [DEFAULT_XMX_MB]. */
    private val maxHeapMbProvider: () -> Int? = { null },
    /** The "Kotlin compiler VMs" setting: how many workers may run at once. Null/0 → [DEFAULT_WORKERS]. */
    private val workerCountProvider: () -> Int? = { null },
    /**
     * Whether THIS process is the one that runs builds. `AndroidIde.createProjectManager` stands up an engine
     * in both the IDE process and the `:build` daemon, and only one of them compiles; a worker is far too
     * expensive to start speculatively in the other. Consulted by [warmUp] only, since a compile arriving at
     * all proves this process builds.
     */
    private val hostsBuilds: () -> Boolean = { true },
    /** Android SDK jar, so a worker can dex a runtime compiler plugin (the ART plugin loader). */
    private val androidJar: Path,
    private val minApi: Int,
    private val fallback: KotlinCompilerBackend,
) : KotlinCompilerBackend {

    private val log = Log.logger("ide.mem")
    private val appContext = context.applicationContext

    /** Working root for the fork: request/response files, the workers' `java.io.tmpdir`, plugin dex cache. */
    private val workRoot: Path = File(appContext.cacheDir, "kotlinc-fork").toPath()

    /**
     * The fork's classpath: the app's own installed APK(s). They carry the dexed compiler, the IntelliJ
     * platform, the ART shims baked in by the `dev.ide.kotlinc-art` instrumentation, AND the resources the
     * compiler reads off its own classloader (`compiler.version`, `META-INF/services`, the bundled
     * `kotlin-stdlib.jar`). Extracted `.dex` files hold classes but no resources, so they cannot serve here.
     */
    private val forkClasspath: String = (
        listOfNotNull(appContext.applicationInfo.sourceDir) +
            (appContext.applicationInfo.splitSourceDirs?.toList() ?: emptyList())
        ).filter { File(it).isFile }.joinToString(File.pathSeparator)

    // --- pool state ---------------------------------------------------------------------------------------
    // `slots` bounds how many workers exist at once; `idle` holds the started-and-healthy ones. A caller takes
    // a slot, then an idle worker or a newly started one, and returns the worker to `idle` when done. A worker
    // that died is simply not returned, so its slot is free for a fresh one.

    private val slots: Semaphore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Semaphore(workerCount(), true) }
    private val idle = ArrayDeque<Worker>()
    private val failures = AtomicInteger()

    @Volatile
    private var forkUnusable: String? = null

    override fun compile(request: KotlinCompileRequest): KotlinCompileResult {
        if (unusableReason() != null) return fallback.compile(request)
        return withWorker(
            onUnavailable = { fallback.compile(request) },
            body = { worker -> worker.compile(request) },
        )
    }

    override fun warmUp(bootClasspath: List<Path>) {
        // A process that will not build must not hold a compiler VM open for a compile that never comes. It
        // still warms the in-process compiler, which is what it would fall back to anyway.
        if (unusableReason() != null || !hostsBuilds()) {
            fallback.warmUp(bootClasspath)
            return
        }
        // Starting the worker IS most of the warm-up (the VM boot and the compiler class-load); the `W`
        // command then pays the environment stand-up inside it, exactly as the in-process warm-up does.
        withWorker(onUnavailable = { fallback.warmUp(bootClasspath) }) { worker -> worker.warmUp(bootClasspath) }
    }

    override fun close() {
        val dead: List<Worker>
        synchronized(idle) {
            dead = idle.toList()
            idle.clear()
        }
        dead.forEach { it.kill() }
        // Slots are not returned here: a worker holding one is mid-compile and will return its own slot.
        log.info("forked-kotlinc: closed ${dead.size} idle worker(s)")
    }

    // --- worker lease --------------------------------------------------------------------------------------

    /**
     * Run [body] on a healthy worker, starting one if the pool has a free slot. Falls back to
     * [onUnavailable] when no worker could be obtained or the call failed, which is always recoverable by
     * the in-process path.
     *
     * A first failure is retried once on a FRESH worker rather than going straight in-process, because the
     * likeliest cause is a worker that exited between compiles: the idle timeout is a designed event, and the
     * OS may reclaim a worker at any time. Neither is a reason to compile a whole module in-process, and
     * neither should count toward [MAX_WORKER_FAILURES] when the retry succeeds, or a few quiet gaps would
     * disable forking for the session. Only a failure that survives the retry is counted and fallen back on.
     */
    private fun <T> withWorker(onUnavailable: () -> T, body: (Worker) -> T): T {
        if (!slots.tryAcquire(SLOT_WAIT_SEC, TimeUnit.SECONDS)) {
            log.warn("forked-kotlinc: no worker slot after ${SLOT_WAIT_SEC}s; compiling in-process")
            return onUnavailable()
        }
        try {
            var lastFailure: Throwable? = null
            for (attempt in 0 until ATTEMPTS) {
                // Only the first attempt may reuse a pooled worker; a retry always starts a fresh VM, since
                // the pooled one is exactly what just failed.
                val worker = (if (attempt == 0) takeIdle() else null) ?: startWorker() ?: break
                try {
                    val result = body(worker)
                    synchronized(idle) { idle.addLast(worker) }   // healthy: back in the pool
                    return result
                } catch (t: Throwable) {
                    worker.kill()
                    lastFailure = t
                    if (attempt < ATTEMPTS - 1 && forkUnusable == null) {
                        log.info("forked-kotlinc: worker lost (${t.message}); retrying on a fresh one")
                    }
                }
            }
            lastFailure?.let { noteFailure(it.message ?: it.javaClass.name) }
            return onUnavailable()
        } finally {
            slots.release()
        }
    }

    private fun takeIdle(): Worker? {
        while (true) {
            val w = synchronized(idle) { idle.pollFirst() } ?: return null
            if (w.isAlive()) return w
            log.info("forked-kotlinc: dropping a worker that exited while idle")
        }
    }

    /** Start a worker at the largest heap this device can back, stepping down the ladder. Null if none start. */
    private fun startWorker(): Worker? {
        val launcher = R8ForkSupport.launcher() ?: run { disable("no forked-VM launcher on this device"); return null }
        val requested = (maxHeapMbProvider() ?: DEFAULT_XMX_MB).coerceAtLeast(MIN_XMX_MB)
        // Drop the heaps this device's RAM visibly cannot back before trying any: a VM that cannot reserve its
        // region space ABORTS, and the OS files that abort under this package as a native crash.
        val candidates = R8ForkSupport.affordableHeaps(
            appContext, (listOf(requested) + FALLBACK_LADDER.filter { it < requested }).distinct(),
        )
        if (candidates.isEmpty()) {
            disable("${R8ForkSupport.totalMemMb(appContext)}MB of device RAM can't back a Kotlin compiler VM")
            return null
        }
        for (xmx in candidates) {
            val worker = runCatching { Worker.start(launcher, xmx, workRoot, forkClasspath, androidJar, minApi, log) }
                .getOrElse {
                    log.warn("forked-kotlinc: worker at ${xmx}MB failed to start: ${it.message}")
                    null
                }
            if (worker != null) {
                log.info(
                    "forked-kotlinc: worker up in $launcher -Xmx${xmx}m " +
                        "(vs the ${Runtime.getRuntime().maxMemory() / MB}MB app heap cap)",
                )
                return worker
            }
        }
        noteFailure("no worker started at ${candidates.last()}MB or above")
        return null
    }

    private fun workerCount(): Int =
        (workerCountProvider()?.takeIf { it > 0 } ?: DEFAULT_WORKERS).coerceIn(1, MAX_WORKERS)

    /** Null while forking may still be attempted; a reason string once it has been abandoned or turned off. */
    private fun unusableReason(): String? {
        forkUnusable?.let { return it }
        if (modeProvider()?.lowercase() == MODE_INPROCESS) return "Kotlin compiler execution = In-process"
        return null
    }

    private fun noteFailure(reason: String) {
        val n = failures.incrementAndGet()
        log.warn("forked-kotlinc: worker failure $n/$MAX_WORKER_FAILURES ($reason); compiling in-process")
        if (n >= MAX_WORKER_FAILURES) disable("$MAX_WORKER_FAILURES worker failures")
    }

    private fun disable(reason: String) {
        if (forkUnusable == null) {
            forkUnusable = reason
            log.warn("forked-kotlinc: $reason → in-process Kotlin compiler for this session")
        }
        close()
    }

    // --- one forked VM -------------------------------------------------------------------------------------

    /**
     * A single live worker VM and the control channel to it. Not thread-safe on its own; the pool hands one
     * out to a single caller at a time.
     */
    private class Worker(
        private val process: Process,
        private val replies: LinkedBlockingQueue<String>,
        private val stderr: StderrTail,
        private val workRoot: Path,
    ) {
        private val writer = process.outputStream.bufferedWriter()

        fun isAlive(): Boolean = process.isAlive

        fun compile(request: KotlinCompileRequest): KotlinCompileResult {
            val id = nextId.incrementAndGet()
            val requestFile = workRoot.resolve("req-$id")
            val responseFile = workRoot.resolve("res-$id")
            try {
                KotlincWire.writeRequest(request, requestFile)
                send("${KotlincWorkerMain.COMPILE} $requestFile $responseFile", COMPILE_TIMEOUT_SEC)
                return KotlincWire.readResult(responseFile)
            } finally {
                runCatching { Files.deleteIfExists(requestFile) }
                runCatching { Files.deleteIfExists(responseFile) }
            }
        }

        fun warmUp(bootClasspath: List<Path>) {
            send("${KotlincWorkerMain.WARM_UP} ${bootClasspath.joinToString(":")}", COMPILE_TIMEOUT_SEC)
        }

        /**
         * Send one command and await its reply. Any non-[KotlincWorkerMain.DONE] answer, a dead worker, or a
         * silent one is thrown, which costs this worker its life and sends the caller in-process.
         */
        private fun send(command: String, timeoutSec: Long) {
            writer.write(command)
            writer.newLine()
            writer.flush()
            val reply = replies.poll(timeoutSec, TimeUnit.SECONDS)
                ?: error("worker did not answer within ${timeoutSec}s${stderr.suffix()}")
            if (reply == EOF) error("worker exited mid-command${stderr.suffix()}")
            if (reply != KotlincWorkerMain.DONE) error("worker replied '$reply'${stderr.suffix()}")
        }

        fun kill() {
            runCatching { writer.close() }                 // closing stdin asks the worker to exit cleanly
            if (!runCatching { process.waitFor(SHUTDOWN_WAIT_SEC, TimeUnit.SECONDS) }.getOrDefault(false)) {
                runCatching { process.destroyForcibly() }
            }
            stderr.stop()
        }

        companion object {
            private val nextId = AtomicInteger()

            fun start(
                launcher: String,
                xmxMb: Int,
                workRoot: Path,
                forkClasspath: String,
                androidJar: Path,
                minApi: Int,
                log: Logger,
            ): Worker? {
                Files.createDirectories(workRoot)
                // The forked VM has no app framework around it, so anything the compiler reads out of the
                // environment has to be handed over explicitly:
                //  - `kotlinc.art.home`: where the patched PathUtil finds the extension-point descriptors that
                //    IntelliJ-core reads off a real filesystem path (AndroidIde publishes it per process).
                //  - `java.io.tmpdir`: a command-line VM defaults to a path that does not exist under an app's
                //    sandbox, and the compiler creates temp files (the inline keep-rule file, the warm-up dir,
                //    the extracted bundled stdlib).
                //  - `kotlin.colors.enabled`: keeps `PlainTextMessageRenderer`'s initializer off jansi, which
                //    is not bundled. The worker never goes through kotlinc's CLI entry point, so nothing should
                //    reach it, but a NoClassDefFoundError there would kill the VM rather than fail one compile.
                val tmpDir = workRoot.resolve("tmp")
                Files.createDirectories(tmpDir)
                val pluginCache = workRoot.resolve("plugins")
                val vmArgs = buildList {
                    add("-Xmx${xmxMb}m")
                    add("-Djava.io.tmpdir=$tmpDir")
                    add("-Dkotlin.colors.enabled=false")
                    System.getProperty("kotlinc.art.home")?.let { add("-Dkotlinc.art.home=$it") }
                }
                val cmd = buildList {
                    add(launcher); addAll(vmArgs)
                    add("-cp"); add(forkClasspath)
                    add(KotlincWorkerMain::class.java.name)
                    add(KotlincWorkerMain.PROTOCOL_ARG); add(KotlincWire.PROTOCOL_VERSION.toString())
                    add(KotlincWorkerMain.ANDROID_JAR_ARG); add(androidJar.toString())
                    add(KotlincWorkerMain.PLUGIN_CACHE_ARG); add(pluginCache.toString())
                    add(KotlincWorkerMain.MIN_API_ARG); add(minApi.toString())
                    add(KotlincWorkerMain.IDLE_EXIT_ARG); add(IDLE_EXIT_SEC.toString())
                }
                val process = ProcessBuilder(cmd).start()
                val replies = LinkedBlockingQueue<String>()
                drain(process.inputStream.bufferedReader(), replies)
                // stderr MUST be drained continuously and separately: merging it into stdout would corrupt the
                // control channel, and leaving it undrained would block the worker once the pipe buffer fills.
                val stderr = StderrTail(process.errorStream.bufferedReader())
                val ready = replies.poll(START_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (ready == null || !ready.startsWith(KotlincWorkerMain.READY)) {
                    log.warn("forked-kotlinc: worker handshake failed (got '${ready ?: "nothing"}')${stderr.suffix()}")
                    runCatching { process.destroyForcibly() }
                    stderr.stop()
                    return null
                }
                return Worker(process, replies, stderr, workRoot)
            }

            private fun drain(reader: BufferedReader, into: LinkedBlockingQueue<String>) {
                val t = Thread({
                    runCatching { while (true) into.put(reader.readLine() ?: break) }
                    into.put(EOF)                              // unblocks a caller waiting on a dead worker
                }, "kotlinc-worker-out")
                t.isDaemon = true
                t.start()
            }
        }
    }

    /** Keeps the worker's stderr drained (so it can never block on a full pipe) and its tail for diagnostics. */
    private class StderrTail(reader: BufferedReader) {
        private val lines = ArrayDeque<String>()
        private val thread = Thread({
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(lines) {
                        lines.addLast(line)
                        if (lines.size > MAX_TAIL_LINES) lines.pollFirst()
                    }
                }
            }
        }, "kotlinc-worker-err").apply { isDaemon = true; start() }

        /** The recent stderr, formatted for a one-line log/exception message, or empty when there is none. */
        fun suffix(): String {
            val tail = synchronized(lines) { lines.toList() }
            return if (tail.isEmpty()) "" else "; worker stderr: ${tail.joinToString(" | ")}"
        }

        fun stop() {
            thread.interrupt()
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val MODE_INPROCESS = "inprocess"

        /** The compiler's own peak is well under this; the headroom is what the app heap cannot offer. */
        const val DEFAULT_XMX_MB = 1536
        const val MIN_XMX_MB = 768
        val FALLBACK_LADDER = listOf(2048, 1536, 1024, 768)

        /**
         * One worker by default. The win here is the warm environment, not parallelism, and a worker is
         * RESIDENT: a second one is another multi-hundred-MB working set held for the whole session, which on
         * a phone competes with the IDE it is meant to protect. Multi-module projects with independent
         * siblings can raise it.
         */
        const val DEFAULT_WORKERS = 1
        const val MAX_WORKERS = 3

        /** Cold VM boot plus the compiler class-load, measured at ~2s; generous for a slow device. */
        const val START_TIMEOUT_SEC = 180L

        /** A ceiling on a single module's compile, so a wedged worker fails the module instead of the build. */
        const val COMPILE_TIMEOUT_SEC = 900L

        /** How long a compile waits for a busy pool before giving up and going in-process. */
        const val SLOT_WAIT_SEC = 900L

        const val SHUTDOWN_WAIT_SEC = 5L

        /** Returns a worker's heap to the device after a quiet session; the next compile starts a fresh one. */
        const val IDLE_EXIT_SEC = 600L

        /** One retry on a fresh worker before a call gives up and goes in-process. */
        const val ATTEMPTS = 2

        /** After this many worker failures (each already retried) the fork is abandoned for the session. */
        const val MAX_WORKER_FAILURES = 3

        const val MAX_TAIL_LINES = 40

        /** Pushed onto the reply queue when the worker's stdout reaches EOF, i.e. the process is gone. */
        const val EOF = "\u0000EOF"
    }
}
