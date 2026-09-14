package dev.ide.build

import dev.ide.platform.Topic

/**
 * Build and run lifecycle events, published by the IDE on the application message bus.
 *
 * A plugin subscribes through its registrar (`reg.busConnection().subscribe(BuildTopics.BUILD, listener)`);
 * that connection is tracked, so the subscription is removed when the plugin unloads. The IDE is the sole
 * publisher, from the point that owns each transition, and every publish is guarded: a listener that throws
 * cannot break a build or a run.
 *
 * Delivery is synchronous, in subscription order, on whatever thread performed the transition. A build runs
 * on a background dispatcher and a program run on its own thread, neither of which is the UI thread, so a
 * listener that touches the UI must marshal.
 */
object BuildTopics {
    val BUILD: Topic<BuildEventListener> = Topic("ide.build", BuildEventListener::class.java)
    val RUN: Topic<RunEventListener> = Topic("ide.run", RunEventListener::class.java)
}

/** A build's lifecycle: a compile or assemble, including the compile half of a run. */
sealed interface BuildEvent {
    data class Started(val module: String, val taskIds: List<String>) : BuildEvent
    data class Finished(
        val module: String,
        val succeeded: Boolean,
        /**
         * A coarse failure bucket when the build failed, null on success. One of `"oom"`, `"compile"`
         * (an error from a compiler, normally the user's own code), `"resource"`, `"tool"` (dexing,
         * packaging or signing), `"no_diagnostic"` (failed without producing a structured error) or
         * `"other"`. The set is open to additions, so treat an unrecognised value as `"other"`.
         */
        val failureKind: String? = null,
        val message: String? = null,
    ) : BuildEvent
}

fun interface BuildEventListener {
    fun onBuildEvent(event: BuildEvent)
}

/** A program run's lifecycle: the interpreted console run, or an Android app launch. */
sealed interface RunEvent {
    data class Started(val module: String, val mainClass: String?) : RunEvent
    /** [exitCode] is null when the run ended without one, such as a cancelled or externally killed process. */
    data class Finished(val module: String, val exitCode: Int?, val succeeded: Boolean) : RunEvent
}

fun interface RunEventListener {
    fun onRunEvent(event: RunEvent)
}
