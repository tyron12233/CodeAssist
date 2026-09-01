package dev.ide.android.fork

import dev.ide.android.ArtKotlinPluginLoader
import dev.ide.lang.kotlin.compile.KotlinCompileResult
import dev.ide.lang.kotlin.compile.KotlinJvmCompiler
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * The entry point of the persistent forked Kotlin compiler VM.
 *
 * Launched by [ForkedKotlinCompiler] as
 * `dalvikvm64 -Xmx<n>m -D… -cp <the app's own APK> dev.ide.android.fork.KotlincWorkerMain …` and then kept
 * alive for the rest of the session. The point of staying alive is the compiler's warm state: standing up
 * IntelliJ-core's application environment and reading `android.jar`, the stdlib and every library jar is
 * seconds of work, and `KotlinEnvironmentKeepAlive` keeps that environment hot across compiles. A VM forked
 * per compile would pay it every time, which measures several times a warm in-process compile; a VM forked
 * once pays it once and then serves compiles at warm speed with a heap the app process cannot have.
 *
 * The classpath is the app's own APK. It carries the dexed compiler, the IntelliJ platform, the ART shims the
 * `dev.ide.kotlinc-art` instrumentation writes into those same classes, and (unlike a bare `.dex` file) the
 * resources the compiler reads off its own classloader, such as `compiler.version` and the bundled
 * `kotlin-stdlib.jar`. So there is no separate compiler asset to ship or extract.
 *
 * Control protocol on stdin/stdout, one line each way, with the compile payload on disk (see [KotlincWire]):
 *
 *     <- READY <protocolVersion>
 *     -> C <requestFile> <responseFile>        compile; answered by DONE or FAIL
 *     -> W <bootClasspath entries, : separated> warm up; answered by DONE
 *     <- DONE
 *     <- FAIL <one-line reason>
 *
 * Requests are served one at a time, in order; the client runs several workers when it wants parallelism.
 * Closing stdin ends the loop and the process. So does [IDLE_EXIT_ARG] seconds without a request, which is
 * how the worker's heap is returned to the device when a session goes quiet.
 */
object KotlincWorkerMain {

    const val PROTOCOL_ARG = "--protocol"
    const val ANDROID_JAR_ARG = "--android-jar"
    const val PLUGIN_CACHE_ARG = "--plugin-cache"
    const val MIN_API_ARG = "--min-api"
    const val IDLE_EXIT_ARG = "--idle-exit-sec"

    const val READY = "READY"
    const val DONE = "DONE"
    const val FAIL = "FAIL"
    const val COMPILE = 'C'
    const val WARM_UP = 'W'

    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.toList().chunked(2).mapNotNull { if (it.size == 2) it[0] to it[1] else null }.toMap()
        if (opts[PROTOCOL_ARG]?.toIntOrNull() != KotlincWire.PROTOCOL_VERSION) {
            // A worker left over from a previous app version would silently drop fields it does not know.
            System.err.println("kotlinc worker: protocol ${opts[PROTOCOL_ARG]} != ${KotlincWire.PROTOCOL_VERSION}")
            exitProcess(2)
        }

        // stdout is the control channel and nothing else. kotlinc and the IntelliJ platform both print to
        // System.out on occasion (a plugin's diagnostics, an environment warning), and one stray line there
        // would be read as a protocol reply and desynchronize the client for the rest of the session. Take a
        // private handle to the real stdout first, then point System.out at stderr, where such output is still
        // captured for the build log.
        val control = PrintStream(FileOutputStream(java.io.FileDescriptor.out), true)
        System.setOut(System.err)

        val compiler = KotlinJvmCompiler(pluginLoader = pluginLoader(opts))
        val idleSeconds = opts[IDLE_EXIT_ARG]?.toLongOrNull() ?: 0L
        val activity = Activity()
        if (idleSeconds > 0) startIdleWatchdog(activity, idleSeconds)

        control.println("$READY ${KotlincWire.PROTOCOL_VERSION}")

        val input = System.`in`.bufferedReader()
        while (true) {
            val line = try {
                input.readLine()
            } catch (t: Throwable) {
                System.err.println("kotlinc worker: control channel closed: ${t.message}")
                null
            } ?: break                                  // client closed stdin, or exited
            activity.touch()
            if (line.isBlank()) continue
            val reply = runCatching { serve(line, compiler) }
                .getOrElse { "$FAIL worker threw: ${it.javaClass.name}: ${flatten(it.message)}" }
            activity.touch()                            // a long compile must not look idle
            control.println(reply)
        }
        exitProcess(0)
    }

    private fun serve(line: String, compiler: KotlinJvmCompiler): String = when (line[0]) {
        COMPILE -> {
            // "C <requestFile> <responseFile>": both are worker-private paths the client just wrote/reserved.
            val parts = line.substring(1).trim().split(' ', limit = 2)
            if (parts.size != 2) "$FAIL malformed compile command" else {
                val result = compile(compiler, Paths.get(parts[0]))
                KotlincWire.writeResult(result, Paths.get(parts[1]))
                DONE
            }
        }

        WARM_UP -> {
            val boot = line.substring(1).trim()
                .split(':').filter { it.isNotEmpty() }.map { Paths.get(it) }
            compiler.warmUp(boot)
            DONE
        }

        else -> "$FAIL unknown command"
    }

    /**
     * A compile that throws must come back as a failed result, not as a dead worker: the exception belongs in
     * the build console next to the module that caused it, and the next module still needs this VM.
     */
    private fun compile(compiler: KotlinJvmCompiler, requestFile: java.nio.file.Path): KotlinCompileResult =
        runCatching { compiler.compile(KotlincWire.readRequest(requestFile)) }
            .getOrElse {
                KotlinCompileResult(
                    success = false,
                    messages = listOf("error: kotlinc threw: ${it.javaClass.name}: ${flatten(it.message)}"),
                )
            }

    /** The ART plugin loader, so a runtime (non-bundled) Kotlin compiler plugin still loads inside the fork.
     *  Absent arguments leave the desktop default, which cannot define a jar's classes on ART but is only
     *  reached when no runtime plugin is in play. */
    private fun pluginLoader(opts: Map<String, String>): dev.ide.lang.kotlin.compile.KotlinPluginLoader {
        val androidJar = opts[ANDROID_JAR_ARG]
        val cache = opts[PLUGIN_CACHE_ARG]
        val minApi = opts[MIN_API_ARG]?.toIntOrNull()
        return if (androidJar != null && cache != null && minApi != null) {
            ArtKotlinPluginLoader(Paths.get(androidJar), Paths.get(cache), minApi)
        } else {
            dev.ide.lang.kotlin.compile.DefaultKotlinPluginLoader
        }
    }

    /** Last time a command arrived or finished, so a long compile is never mistaken for an idle session. */
    private class Activity {
        @Volatile
        var lastMillis: Long = System.currentTimeMillis()

        fun touch() {
            lastMillis = System.currentTimeMillis()
        }
    }

    /**
     * Returns the worker's heap to the device when the session goes quiet. The client notices the dead
     * process on its next compile and starts a fresh worker, so exiting here is invisible apart from one cold
     * start. A daemon thread, so it cannot by itself keep the VM alive.
     */
    private fun startIdleWatchdog(activity: Activity, idleSeconds: Long) {
        val thread = Thread({
            val idleMillis = idleSeconds * 1000
            while (true) {
                val remaining = idleMillis - (System.currentTimeMillis() - activity.lastMillis)
                if (remaining <= 0) {
                    System.err.println("kotlinc worker: idle for ${idleSeconds}s, exiting")
                    exitProcess(0)
                }
                try {
                    Thread.sleep(remaining.coerceAtLeast(1000))
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "kotlinc-worker-idle")
        thread.isDaemon = true
        thread.start()
    }

    /** Keeps a reply on one line: the control channel is line-delimited. */
    private fun flatten(message: String?): String =
        message.orEmpty().lineSequence().joinToString(" ") { it.trim() }.trim().ifEmpty { "(no message)" }
}
