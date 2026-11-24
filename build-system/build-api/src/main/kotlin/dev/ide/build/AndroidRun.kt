package dev.ide.build

import dev.ide.model.Module
import dev.ide.platform.ExtensionPoint

/**
 * Joins the host's Run of an Android app module: what the launch intent carries, and what happens in the Run
 * console once the app is up.
 *
 * The host builds, installs and launches the app as for any Android module; a participant only adds to that. A
 * Flutter plugin uses it to run the app the way `flutter run` does: the launch asks the debug engine for a known
 * VM service port, and the session after it attaches to that VM and hot-reloads the app's code as it is saved,
 * in the app the user ships, with its plugins, rather than in a stand-in.
 *
 * The first participant that answers [launchExtras] with non-null takes part in a run; the others stay out of it.
 */
interface AndroidRunParticipant {
    /**
     * Extras for the launch intent of [module]'s [variant] (values: String, Int, Long or Boolean), or null when
     * this participant has nothing to do with the run. Called before the build starts.
     */
    fun launchExtras(module: Module, variant: String): Map<String, Any>?

    /** The Run console's title while [afterLaunch] runs ("Flutter"). */
    val consoleTitle: String

    /**
     * After the app was installed and its launch requested with [launchExtras]: a session in the Run console,
     * reading [io]'s input and writing its output. The run lasts until this returns or the user stops it, which
     * cancels it. The launch itself happens asynchronously (the system may also ask the user to confirm the
     * install first), so a session that connects to the app retries for a while.
     */
    suspend fun afterLaunch(module: Module, variant: String, io: ProgramIo, log: (String) -> Unit)
}

/** Contributed [AndroidRunParticipant]s, consulted by the host's Run of an Android app module. */
val ANDROID_RUN_PARTICIPANT_EP = ExtensionPoint<AndroidRunParticipant>("platform.androidRunParticipant")
