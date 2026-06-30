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
    void open(String workspaceDir, int modelGeneration);

    // The runnable tasks for the open project, each encoded "id\tlabel\tgroup". Valid after onOpened(true).
    String[] runTasks();

    // Launch a specific task / the default build / cancel. Build state streams back via the callback deltas.
    void runTask(String id);
    void runBuild();
    void stopBuild();

    // --- Phase 4: interactive run (the program runs in :build; these drive its stdin + the sandbox prompts).
    void sendRunInput(String text);   // feed one line of stdin to the running program
    void closeRunInput();             // EOF the program's stdin
    void answerPermission(int id, int decision); // answer a pending sandbox prompt (UiPermissionDecision ordinal)
}
