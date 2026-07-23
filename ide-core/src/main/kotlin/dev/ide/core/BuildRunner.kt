package dev.ide.core

import dev.ide.ui.backend.AppLogUi
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * The swappable seam between the UI and the actual build/run engine. Mirrors the build/run subset of
 * [dev.ide.ui.backend.BuildService] exactly, so the backend ([dev.ide.core.backend.BuildBackend]) routes
 * every build/run call through it without knowing where the work runs.
 *
 * Today only [InProcessBuildRunner] exists — build/run in the IDE's own process. A future RemoteBuildRunner
 * (in :ide-android) will implement this same surface over IPC to a separate `:build` OS process, so a build
 * OOM or a user-program crash kills only that process, not the IDE. See `docs/build-process-isolation.md`.
 */
interface BuildRunner {
    val buildState: StateFlow<BuildState>
    val runConsole: StateFlow<RunConsoleUi?>
    val permissionRequest: StateFlow<UiPermissionRequest?>
    val appLog: StateFlow<AppLogUi>
    fun runTasks(): List<RunTaskOption>
    fun runTask(id: String)
    fun runBuild()
    fun stopBuild()
    fun sendRunInput(text: String)
    fun closeRunInput()
    fun answerPermission(id: Int, decision: UiPermissionDecision)
    fun clearAppLog()
}

/**
 * Runs the build/run in the IDE's own process by delegating straight to the workspace [BuildService] — the
 * behavior that existed before the seam. No logic moved here; this is purely the in-process arm of
 * [BuildRunner].
 */
internal class InProcessBuildRunner(private val build: dev.ide.core.services.BuildService) : BuildRunner {
    override val buildState: StateFlow<BuildState> get() = build.buildState
    override val runConsole: StateFlow<RunConsoleUi?> get() = build.runConsole
    override val permissionRequest: StateFlow<UiPermissionRequest?> get() = build.permissionRequest
    override val appLog: StateFlow<AppLogUi> get() = build.appLog
    override fun clearAppLog() = build.clearAppLog()
    override fun runTasks(): List<RunTaskOption> = build.runTasks()
    override fun runTask(id: String) = build.runTask(id)
    override fun runBuild() = build.runBuild()
    override fun stopBuild() = build.stopBuild()
    override fun sendRunInput(text: String) = build.sendRunInput(text)
    override fun closeRunInput() = build.closeRunInput()
    override fun answerPermission(id: Int, decision: UiPermissionDecision) = build.answerPermission(id, decision)
}
