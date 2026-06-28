package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import kotlinx.coroutines.flow.StateFlow

/** [BuildService] over the engine: build/run state, the run-task list, interactive console I/O, and the
 *  run-sandbox permission prompts. The observable flows re-point to the live engine on each project swap. */
internal class BuildBackend(private val ctx: BackendContext) : BuildService {
    override val buildState: StateFlow<BuildState> = ctx.engineFlow(BuildState()) { it.buildState }
    override fun runTasks(): List<RunTaskOption> = ctx.services.runTasks()
    override fun runTask(id: String) = ctx.services.runTask(id)
    override fun runBuild() = ctx.services.runBuild()
    override fun stopBuild() = ctx.services.stopBuild()

    override val runConsole: StateFlow<RunConsoleUi?> = ctx.engineFlow<RunConsoleUi?>(null) { it.runConsole }
    override fun sendRunInput(text: String) = ctx.services.sendRunInput(text)
    override fun closeRunInput() = ctx.services.closeRunInput()

    override val permissionRequest: StateFlow<UiPermissionRequest?> =
        ctx.engineFlow<UiPermissionRequest?>(null) { it.permissionRequest }
    override fun answerPermission(id: Int, decision: UiPermissionDecision) = ctx.services.answerPermission(id, decision)
}
