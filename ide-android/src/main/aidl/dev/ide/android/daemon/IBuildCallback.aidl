// IPC for build-process isolation (docs/build-process-isolation.md). Daemon (:build) -> UI events.
package dev.ide.android.daemon;

interface IBuildCallback {
    // oneway: fire-and-forget, ordered per-binder, never blocks the daemon. These carry BuildState DELTAS
    // (the UI reassembles the full state on its side). Enums travel as their .name() string, decoded with
    // RunStatus.valueOf / StepStatus.valueOf — safe because both processes run the same dexed classes.
    oneway void onOpened(boolean ok, String error);              // open(workspaceDir) completed
    oneway void onStatus(String status, String moduleName, long elapsedMs); // RunStatus changed
    oneway void onStep(String name, String status);              // a build step's StepStatus changed
    oneway void onLog(String message);                           // one new build-log line
    // one new structured diagnostic (the Problems tab). severity travels as UiSeverity.name(); file/detail/task nullable.
    oneway void onDiagnostic(String severity, String message, String kind, String source, String file, int line, int column, String detail, String task);

    // --- Phase 4: run-user-code (the interactive console). The daemon hosts the dex-run; these stream the
    // program's stdio + lifecycle + the run-sandbox permission prompts back to the UI. Enums travel as
    // ordinals (RunPhase / ConsoleChunkKind), decoded by index — same dexed classes both processes.
    // RunConsoleUi scalar state; runId < 0 means the console cleared (null). exitCode valid only if hasExit.
    oneway void onRunConsole(int runId, String moduleName, String mainClass, int phase, boolean acceptsInput, boolean hasExit, int exitCode);
    oneway void onConsoleChunk(int runId, String text, int kind); // one new transcript chunk (output/input/system)
    oneway void onPermission(int reqId, String category, String detail); // pending sandbox prompt; reqId < 0 = cleared

    // The android "Run" just installed [packageName]; launch it HERE in the UI process. The install runs in
    // :build, but firing the installed app's activity from that background process is blocked by Android's
    // background-activity-launch rules — the UI has a foreground activity, so it can.
    oneway void onLaunchPackage(String packageName);
}
