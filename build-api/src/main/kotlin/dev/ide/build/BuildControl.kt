package dev.ide.build

import dev.ide.platform.ServiceKey

/**
 * Starting, stopping, and capturing a build: the slice of the engine's build service a plugin can name.
 * WORKSPACE-scoped, so it is resolved from the open project's `Workspace`.
 *
 * Narrow on purpose. The engine's own build service is a far wider class, and most of its width is phrased
 * in the console, run-picker and permission types the IDE's UI port owns, which are not plugin API and
 * carry no compatibility promise. What is here is what a plugin outside the IDE can act on without naming
 * any of that.
 */
interface BuildControl {

    /** Start a build of the open project. Returns immediately; progress goes to the build console. */
    fun runBuild()

    /** Stop the build or the program currently running. A no-op when neither is. */
    fun stopBuild()

    /**
     * Compile [moduleName] and run its `main`, capturing what the program writes instead of routing it to
     * the run console. Suspends until the program exits, [timeoutMs] elapses, or compilation fails. [stdin]
     * is fed to it as standard input.
     *
     * A module that does not exist, has no runnable `main`, or fails to compile comes back as a
     * [RunCapture] carrying the reason in [RunCapture.diagnostics], not as an exception.
     */
    suspend fun runAndCapture(moduleName: String, stdin: String = "", timeoutMs: Long = 60_000): RunCapture
}

/**
 * Outcome of [BuildControl.runAndCapture]: whether the program [compiled] (its `main` started), [ran] to
 * completion, its captured [stdout], its [exitCode] (null if it never finished or timed out), and any
 * compile-error [diagnostics].
 */
data class RunCapture(
    val compiled: Boolean,
    val ran: Boolean,
    val stdout: String,
    val exitCode: Int?,
    val diagnostics: List<String>,
)

/** WORKSPACE-scoped [BuildControl] for the open project. */
val BUILD_CONTROL = ServiceKey<BuildControl>("platform.buildControl")
