package com.tyron.builder.internal.aapt.v2

import com.android.SdkConstants
import com.android.ide.common.resources.CompileResourceRequest
import com.android.utils.ILogger
import com.tyron.builder.internal.aapt.AaptConvertConfig
import com.tyron.builder.internal.aapt.AaptPackageConfig
import java.util.concurrent.TimeoutException
import javax.annotation.concurrent.NotThreadSafe

/**
 * Manages an AAPT2 daemon process. Implementations are not expected to be thread safe.
 *
 * This must be used in the following sequence:
 * Call [compile] or [link] as many times as needed.
 * These methods block until the operation requested is complete.
 * The first call to either of [compile] or [link] will start the underlying daemon process.
 * Call [shutDown()], which blocks until the daemon process has exited.
 *
 * Processes cannot be re-started.
 *
 * The state tracking in this class is separated from the actual process handling to allow test
 * fakes that do not actually use AAPT.
 */
@NotThreadSafe
abstract class Aapt2Daemon(
        protected val displayName: String,
        protected val logger: ILogger) : Aapt2 {
    enum class State { NEW, RUNNING, SHUTDOWN }

    var state: State = State.NEW
        private set

    private fun checkStarted() {
        when (state) {
            State.NEW -> {
                logger.verbose("%1\$s: starting", displayName)
                try {
                    startProcess()
                } catch (e: TimeoutException) {
                    handleError("Daemon startup timed out", e)
                } catch (e: Exception) {
                    // AAPT2 is supported on Windows 32-bit and 64-bit, Linux 64-bit and MacOS 64-bit.
                    // If it fails because of the incompatible system, inform the user of the reason.
                    if (SdkConstants.currentPlatform() != SdkConstants.PLATFORM_WINDOWS
                        && !System.getProperty("os.arch").contains("64")) {

                        handleError(
                            "AAPT2 is not supported on 32-bit ${SdkConstants.currentPlatformName()}," +
                                    " see supported systems on https://developer.android.com/studio#system-requirements-a-namerequirementsa",
                            IllegalStateException("Unsupported operating system."),
                            false)
                    } else {
                        var message = "Daemon startup failed"
                        if (SdkConstants.currentPlatform() == SdkConstants.PLATFORM_WINDOWS) {
                            // https://issuetracker.google.com/131883685
                            message +=
                                    "\nPlease check if you installed the Windows Universal C Runtime."
                        }
                        handleError(message, e)
                    }
                }
                state = State.RUNNING
            }
            State.RUNNING -> {
                // Already ready
            }
            State.SHUTDOWN -> error("$displayName: Cannot restart a shutdown process")
        }
    }

    /**
     * Implementors must start the underlying AAPT2 daemon process.
     *
     * This will be called before any calls to [doCompile] or [doLink].
     *
     * If the daemon process could not be started, this method must throw an exception,
     * having attempted to free any used resources (e.g. threads, processes).
     */
    protected abstract fun startProcess()

    override fun compile(request: com.android.ide.common.resources.CompileResourceRequest, logger: ILogger) {
        checkStarted()
        try {
            doCompile(request, logger)
        } catch (e: Aapt2Exception) {
            // Propagate errors in the users sources directly.
            throw e
        } catch (e: TimeoutException) {
            handleError("Compile '${request.inputFile}' timed out", e)
        } catch (e: Exception) {
            handleError("Unexpected error during compile '${request.inputFile}'", e)
        }
    }

    /**
     * Implementors must compile the file in the request given.
     *
     * This will only be called after [startProcess] is called and before [stopProcess] is called
     */
    @Throws(TimeoutException::class, Aapt2InternalException::class, Aapt2Exception::class)
    protected abstract fun doCompile(request: CompileResourceRequest, logger: ILogger)

    override fun link(request: AaptPackageConfig, logger: ILogger) {
        checkStarted()
        try {
            doLink(request, logger)
        } catch (e: Aapt2Exception) {
            // Propagate errors in the users sources directly.
            throw e
        } catch (e: TimeoutException) {
            handleError("Link timed out", e)
        } catch (e: Exception) {
            handleError("Unexpected error during link", e)
        }
    }

    /**
     * Implementors must perform the link operation given.
     *
     * This will only be called after [startProcess] is called and before [stopProcess] is called.
     */
    @Throws(TimeoutException::class, Aapt2InternalException::class, Aapt2Exception::class)
    protected abstract fun doLink(request: AaptPackageConfig, logger: ILogger)

    override fun convert(request: AaptConvertConfig, logger: ILogger) {
        checkStarted()
        try {
            doConvert(request, logger)
        } catch (e: Aapt2Exception) {
            // Propagate errors in the users sources directly.
            throw e
        } catch (e: TimeoutException) {
            handleError("Convert timed out", e)
        } catch (e: Exception) {
            handleError("Unexpected error during convert", e)
        }
    }

    /**
     * Implementors must perform the convert operation given.
     *
     * This will only be called after [startProcess] is called and before [stopProcess] is called.
     */
    @Throws(TimeoutException::class, Aapt2InternalException::class, Aapt2Exception::class)
    protected abstract fun doConvert(request: AaptConvertConfig, logger: ILogger)

    fun shutDown() {
        state = when (state) {
            State.NEW -> State.SHUTDOWN // Never started, nothing to do.
            State.RUNNING -> {
                logger.verbose("%1\$s: shutdown", displayName)
                try {
                    stopProcess()
                } catch (e: TimeoutException) {
                    logger.error(e, "$displayName Failed to shutdown within timeout")
                }
                State.SHUTDOWN
            }
            State.SHUTDOWN -> error("Cannot call shutdown multiple times")
        }
    }

    /**
     * Implementors must stop the underlying AAPT2 daemon process.
     *
     * Will only be called if the process was started by [startProcess].
     */
    @Throws(TimeoutException::class)
    protected abstract fun stopProcess()

    /** Wrap and propagate the original exception, but also try to shut down the daemon. */
    private fun handleError(
        action: String, exception: Exception, unexpected: Boolean = true
    ): Nothing {
        throw Aapt2InternalException(
                    "$displayName: $action" +
                            (if (state == State.RUNNING)
                                ", attempting to stop daemon.\n"
                            else
                                "\n") +
                            (if (unexpected)
                                "This should not happen under normal circumstances, " +
                                        "please file an issue if it does."
                            else
                                ""),
                    exception).apply {
            try {
                shutDown()
            } catch (suppressed: Exception) {
                addSuppressed(suppressed)
            }
        }
    }
}