package dev.ide.android.daemon

import android.content.Context
import android.os.Process
import dev.ide.android.AndroidIde
import dev.ide.platform.log.Log
import dev.ide.ui.backend.RunStatus

/**
 * Phase-3a proof for build-process isolation (docs/build-process-isolation.md): runs a REAL build of an
 * existing project entirely in the `:build` process and streams its build state back over IPC. Verifiable
 * from logcat (`adb logcat -s ide.daemon ide.mem`):
 *
 *  1. bind the daemon, open the first on-device project (heavy: model load + init, in `:build`),
 *  2. run the project's default build there — watch step/log/status deltas stream to the UI,
 *  3. the build finishes (or OOMs, in which case the IDE still survives — see [BuildDaemonClient]).
 *
 * The `:build` process's own `ide.mem` lines show the build's heap profile, now isolated from the IDE's.
 * Throwaway: replaced in Phase 3b by `RemoteBuildRunner` wired into the UI Run button. Gated behind
 * [ENABLED] + `BuildConfig.DEBUG` at the call site.
 */
object BuildDaemonProof {
    /** Off now that Phase 3a is proven and Phase 3b routes real builds through the daemon via the UI Run
     *  button ([RemoteBuildRunner]); leaving the proof on would open a project + build at startup, competing
     *  with the real path. Flip true only to re-run the standalone 3a proof. */
    const val ENABLED = false

    private val log = Log.logger("ide.daemon")

    fun run(context: Context) {
        val project = AndroidIde.projectsDir(context).listFiles()?.firstOrNull { it.isDirectory }
        if (project == null) {
            log.info("PROOF: no on-device project to build via :build; create one first, then relaunch.")
            return
        }

        // `lateinit` so onOpened (a constructor arg) can call back into the same client to start the build.
        lateinit var client: BuildDaemonClient
        client = BuildDaemonClient(
            context,
            onOpened = { ok, err ->
                if (ok) {
                    log.info("PROOF: daemon opened '${project.name}' — running its default build in :build…")
                    client.runBuild()
                } else {
                    log.warn("PROOF: daemon failed to open '${project.name}': $err")
                }
            },
            onStatus = { status, module, elapsed ->
                log.info("PROOF <- status=$status module=$module elapsed=${elapsed}ms")
                if (status == RunStatus.Succeeded.name || status == RunStatus.Failed.name) {
                    log.info("PROOF end: build of '${project.name}' finished in :build = $status; ui pid=${Process.myPid()} alive ✓")
                }
            },
            onStep = { name, status -> log.info("PROOF <- step $name = $status") },
            onLog = { msg -> log.info("PROOF <- $msg") },
        )
        log.info("PROOF start: ui pid=${Process.myPid()} — building '${project.name}' in the :build process")
        client.bind { client.open(project.absolutePath, 0) }
    }
}
