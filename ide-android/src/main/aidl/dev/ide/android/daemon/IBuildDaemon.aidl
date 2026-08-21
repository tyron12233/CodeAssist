// IPC for build-process isolation (docs/build-process-isolation.md). UI -> daemon commands, served by
// BuildDaemonService in the ":build" OS process, which hosts a real headless build engine (ProjectManager +
// IdeServices.buildRunner) so builds run off the IDE's heap. Phase 4 adds run-user-code + stdin/permission.
package dev.ide.android.daemon;

import dev.ide.android.daemon.IBuildCallback;

interface IBuildDaemon {
    // The :build process id, so the UI can confirm the daemon runs in a DIFFERENT process.
    int pid();

    // Register the stream-back channel; the daemon holds it and pushes oneway build-state deltas.
    void registerCallback(IBuildCallback cb);

    // Open the project at [workspaceDir] into a headless engine in this process. Heavy (model load + init),
    // so it runs off the Binder thread and replies via IBuildCallback.onOpened. [modelGeneration] is the UI
    // model's revision: when it differs from the one the daemon last opened at, the on-disk module.toml
    // changed (e.g. minifyEnabled toggled) and the daemon reloads instead of reusing its stale model.
    // [requestId] is echoed back in onOpened so the UI can pair each reply with the request that caused it —
    // a reply to a superseded open (a retry raced it) must not trigger the newer request's queued build.
    void open(String workspaceDir, int modelGeneration, int requestId);

    // The runnable tasks for the open project, each encoded "id\tlabel\tgroup". Valid after onOpened(true).
    String[] runTasks();

    // Launch a specific task / the default build / cancel. Build state streams back via the callback deltas.
    void runTask(String id);
    void runBuild();
    void stopBuild();

    // --- Phase 4: interactive run (the program runs in :build; these drive its stdin + the sandbox prompts).
    void sendRunInput(String text);   // feed one line of stdin to the running program
    void closeRunInput();             // EOF the program's stdin
    // Drive a WINDOWED program (a Swing app) whose frames the UI is showing. Actions are RunPointer/RunKey
    // constants; x/y are in the frame's pixel space. The surface size lets the program lay out at exactly the
    // size it is drawn at instead of being scaled to fit it.
    oneway void sendRunPointer(int action, float x, float y);
    oneway void sendRunKey(int action, int keyCode, int keyChar);
    oneway void sendRunScroll(float x, float y, int notches);
    oneway void setRunSurfaceSize(int widthPx, int heightPx);
    void answerPermission(int id, int decision); // answer a pending sandbox prompt (UiPermissionDecision ordinal)
    void clearAppLog();               // clear the app-log (Logcat) buffer

    // --- App-log relay (docs/app-log-forwarding.md). The exported AppLogSinkService the built debug app binds
    // to always runs in the UI process, but under build-process isolation the app-log CHANNEL lives here in
    // ":build" (the daemon owns the run). So the UI-process sink forwards each batch of wire frames here, where
    // AppLogSinkRegistry.active is the channel started for the run. oneway — never block the sink's binder thread.
    oneway void submitAppLogFrames(in String[] frames); // a batch of AppLogWire payloads from the built app
    oneway void appLogClientGone();                      // the built app unbound the sink (its process went away)
}
