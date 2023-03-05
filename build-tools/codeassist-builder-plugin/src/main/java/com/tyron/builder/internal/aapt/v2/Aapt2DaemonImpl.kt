package com.tyron.builder.internal.aapt.v2

import com.android.utils.GrabProcessOutput
import com.android.utils.ILogger
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.SettableFuture
import com.android.ide.common.resources.CompileResourceRequest
import com.tyron.builder.internal.aapt.AaptConvertConfig
import com.tyron.builder.internal.aapt.AaptPackageConfig
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeoutException

/**
 * The real AAPT2 process.
 *
 * Loosely based on [com.android.builder.png.AaptProcess], but with much of the concurrency removed.
 *
 * See [Aapt2Daemon] docs for more information.
 */
class Aapt2DaemonImpl(
        displayId: String,
        private val aaptPath: String,
        private val aaptCommand: List<String>,
        versionString: String,
        private val daemonTimeouts: Aapt2DaemonTimeouts,
        logger: ILogger) :
        Aapt2Daemon(
                displayName = "AAPT2 $versionString Daemon $displayId",
                logger = logger) {

    constructor(
            displayId: String,
            aaptExecutable: Path,
            daemonTimeouts: Aapt2DaemonTimeouts,
            logger: ILogger) :
            this(
                    displayId = displayId,
                    aaptPath = aaptExecutable.toFile().absolutePath,
                    aaptCommand = listOf(
                            aaptExecutable.toFile().absolutePath,
                            Aapt2DaemonUtil.DAEMON_MODE_COMMAND),
                    versionString = aaptExecutable.parent.fileName.toString(),
                    daemonTimeouts = daemonTimeouts,
                    logger = logger)

    private val noOutputExpected = NoOutputExpected(displayName, logger)

    private lateinit var process: Process
    private lateinit var writer: Writer

    private val processOutput = object : GrabProcessOutput.IProcessOutput {
        @Volatile
        var delegate: GrabProcessOutput.IProcessOutput = noOutputExpected

        override fun out(line: String?) = delegate.out(line)
        override fun err(line: String?) = delegate.err(line)
    }

    @Throws(TimeoutException::class)
    override fun startProcess() {
        val waitForReady = WaitForReadyOnStdOut(displayName, logger)
        processOutput.delegate = waitForReady
        val processBuilder = ProcessBuilder(aaptCommand)
        process = processBuilder.start()
        writer = try {
            GrabProcessOutput.grabProcessOutput(
                    process,
                    GrabProcessOutput.Wait.ASYNC,
                    processOutput)
            process.outputStream.bufferedWriter(Charsets.UTF_8)
        } catch (e: Exception) {
            // Something went wrong with starting the process or reader threads.
            // Propagate the original exception, but also try to forcibly shutdown the process.
            throw e.apply {
                try {
                    process.destroyForcibly()
                } catch (suppressed: Exception) {
                    addSuppressed(suppressed)
                }
            }
        }

        val ready = try {
            waitForReady.future.get(daemonTimeouts.start, daemonTimeouts.startUnit)
        } catch (e: TimeoutException) {
            stopQuietly("Failed to start AAPT2 process $displayName. " +
                    "Not ready within ${daemonTimeouts.start} " +
                    "${daemonTimeouts.startUnit.name.lowercase(Locale.US)}.", e)
        } catch (e: Exception) {
            stopQuietly("Failed to start AAPT2 process.", e)
        }
        if (!ready) {
            stopQuietly("Failed to start AAPT2 process.",
                IOException("Process unexpectedly exit."))
        }
        //Process is ready
        processOutput.delegate = noOutputExpected
    }

    /**
     * Something went wrong with the daemon startup.
     *
     * Propagate the original exception, but also try to shutdown the process.
     */
    private fun stopQuietly(message: String, e: Exception): Nothing {
        throw Aapt2InternalException(message, e).apply {
            try {
                stopProcess()
            } catch (suppressed: Exception) {
                addSuppressed(suppressed)
            }
        }
    }

    @Throws(TimeoutException::class, Aapt2InternalException::class, Aapt2Exception::class)
    override fun doCompile(request: CompileResourceRequest, logger: ILogger) {
        val waitForTask = WaitForTaskCompletion(displayName, logger)
        try {
            processOutput.delegate = waitForTask
            Aapt2DaemonUtil.requestCompile(writer, request)
            // Temporary workaround for b/111629686, manually generate the partial R file for raw and non xml res.
            request.partialRFile?.apply {
                if (request.inputDirectoryName.startsWith("raw") || !request.inputFile.path.endsWith(".xml")) {
                    val type = request.inputDirectoryName.substringBefore('-')
                    val nameWithoutExtension = request.inputFile.name.substringBefore('.')
                    Files.write(toPath(), ImmutableList.of("default int $type $nameWithoutExtension"))
                }
            }
            when (val result = waitForTask.future.get(daemonTimeouts.compile, daemonTimeouts.compileUnit)) {
                is WaitForTaskCompletion.Result.Succeeded -> {}
                is WaitForTaskCompletion.Result.Failed -> {
                    val args = makeCompileCommand(request).joinToString(" \\\n        ")
                    throw Aapt2Exception.create(
                        logger = logger,
                        description = "Android resource compilation failed",
                        output = result.stdErr,
                        processName = displayName,
                        command = "$aaptPath compile $args"
                    )
                }
                is WaitForTaskCompletion.Result.InternalAapt2Error -> {
                    throw result.failure
                }
            }
        } finally {
            processOutput.delegate = noOutputExpected
        }
    }

    @Throws(TimeoutException::class, Aapt2InternalException::class, Aapt2Exception::class)
    override fun doLink(request: AaptPackageConfig, logger: ILogger) {
        val waitForTask = WaitForTaskCompletion(displayName, logger)
        try {
            processOutput.delegate = waitForTask
            Aapt2DaemonUtil.requestLink(writer, request)
            when (val result = waitForTask.future.get(daemonTimeouts.link, daemonTimeouts.linkUnit)) {
                is WaitForTaskCompletion.Result.Succeeded -> { }
                is WaitForTaskCompletion.Result.Failed -> {
                    val configWithResourcesListed =
                        if (request.intermediateDir != null) {
                            request.copy(listResourceFiles = true)
                        } else {
                            request
                        }
                    val args =
                        makeLinkCommand(configWithResourcesListed).joinToString("\\\n        ")

                    throw Aapt2Exception.create(
                        logger = logger,
                        description = "Android resource linking failed",
                        output = result.stdErr,
                        processName = displayName,
                        command = "$aaptPath link $args"
                    )
                }
                is WaitForTaskCompletion.Result.InternalAapt2Error -> {
                    throw result.failure
                }
            }
        } finally {
            processOutput.delegate = noOutputExpected
        }
    }

    override fun doConvert(request: AaptConvertConfig, logger: ILogger) {
        val waitForTask = WaitForTaskCompletion(displayName, logger)
        try {
            processOutput.delegate = waitForTask
            Aapt2DaemonUtil.requestConvert(writer, request)
            when (val result = waitForTask.future.get(daemonTimeouts.link, daemonTimeouts.linkUnit)) {
                is WaitForTaskCompletion.Result.Succeeded -> { }
                is WaitForTaskCompletion.Result.Failed -> {
                    val args = makeConvertCommand(request).joinToString("\\\n        ")

                    throw Aapt2Exception.create(
                        logger = logger,
                        description = "Android resource linking failed",
                        output = result.stdErr,
                        processName = displayName,
                        command = "$aaptPath convert $args"
                    )
                }
                is WaitForTaskCompletion.Result.InternalAapt2Error -> {
                    throw result.failure
                }
            }
        } finally {
            processOutput.delegate = noOutputExpected
        }
    }

    @Throws(TimeoutException::class)
    override fun stopProcess() {
        processOutput.delegate = AllowShutdown(displayName, logger)
        var suppressed: Exception? = null
        try {
            writer.write("quit\n\n")
            writer.flush()
        } catch (e: IOException) {
            // This might happen if the process has already exited.
            suppressed = e
        }

        val shutdown = process.waitFor(daemonTimeouts.stop, daemonTimeouts.stopUnit)
        if (shutdown) {
            return
        }
        throw TimeoutException(
                "$displayName: Failed to shut down within " +
                        "${daemonTimeouts.stop} " +
                        "${daemonTimeouts.stopUnit.name.lowercase(Locale.US)}. " +
                        "Forcing shutdown").apply {
            suppressed?.let { addSuppressed(it) }
            try {
                process.destroyForcibly()
            } catch (suppressed: Exception) {
                addSuppressed(suppressed)
            }
        }
    }

    class NoOutputExpected(private val displayName: String,
            val logger: ILogger) : GrabProcessOutput.IProcessOutput {
        override fun out(line: String?) {
            if (line != null) {
                logger.error(null, "$displayName: Unexpected standard output: $line")
            }
        }

        override fun err(line: String?) {
            if (line != null) {
                logger.error(null, "$displayName: Unexpected error output: $line")
            } else {
                // Don't try to handle process exit here, just allow the next task to fail.
                logger.error(null, "$displayName: Idle daemon unexpectedly exit. This should not happen.")
            }
        }
    }

    class WaitForReadyOnStdOut(private val displayName: String,
            val logger: ILogger) : GrabProcessOutput.IProcessOutput {

        val future: SettableFuture<Boolean> = SettableFuture.create()

        override fun out(line: String?) {
            when (line) {
                null -> {
                    // This could happen just after "Ready" but before this IProcessOutput has been
                    // swapped out. In that case, the task immediately after this will fail anyway.
                    if (!future.isDone) {
                        future.set(false)
                    }
                }
                "" -> return
                "Ready" -> future.set(true)
                else -> {
                    logger.error(null, "$displayName: Unexpected error output: $line")
                }
            }
        }

        override fun err(line: String?) {
            line?.let {
                logger.error(null, "$displayName: Unexpected error output: $it")
            }
        }
    }

    class WaitForTaskCompletion(
            private val displayName: String,
            val logger: ILogger) : GrabProcessOutput.IProcessOutput {

        sealed class Result {
            object Succeeded : Result()
            class Failed(val stdErr: String) : Result()
            class InternalAapt2Error(val failure: IOException) : Result()
        }

        /** Set to null on success, the error output on failure. */
        val future = SettableFuture.create<Result>()!!
        private var errors: StringBuilder? = null
        private var foundError: Boolean = false

        override fun out(line: String?) {
            line?.let { logger.lifecycle("%1\$s: %2\$s", displayName, it) }
        }

        override fun err(line: String?) {
            when (line) {
                null -> {
                    foundError = true
                    if (errors == null) {
                        errors = StringBuilder()
                    }
                    future.set(
                        Result.InternalAapt2Error(IOException(
                            "AAPT2 process unexpectedly exit. Error output:\n" +
                            errors.toString())))
                }
                "Done" -> {
                    when {
                        foundError -> future.set(Result.Failed(errors!!.toString()))
                        errors != null -> {
                            logger.warning(errors.toString())
                            future.set(Result.Succeeded)
                        }
                        else -> future.set(Result.Succeeded)
                    }
                    errors = null
                }
                "Error" -> {
                    foundError = true
                    if (errors == null) {
                        errors = StringBuilder()
                    }
                }
                else -> {
                    if (errors == null) {
                        errors = StringBuilder()
                    }
                    errors!!.append(line).append('\n')
                }
            }
        }
    }

    class AllowShutdown(private val displayName: String,
            val logger: ILogger) : GrabProcessOutput.IProcessOutput {

        override fun out(line: String?) {
            when (line) {
                null, "", "Exiting daemon" -> return
                else -> logger.error(null, "$displayName: Unexpected standard output: $line")
            }
        }

        override fun err(line: String?) {
            line?.let {
                logger.error(null, "$displayName: Unexpected error output: $line")
            }
        }
    }

}
