// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.ide.ui.CodeAssistApp
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiOpenTabs
import dev.ide.ui.backend.TreeViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Playground as an IDE project: one open project, `/project`, holding `Main.kt`, so the whole desktop shell (file
 * tree, tabs, toolbar, status bar, settings) has something to show and the editor inside it gets its completion,
 * diagnostics, colours and the rest from the engine worker through [PlaygroundBackend].
 */
class PlaygroundIdeBackend(engine: EngineClient) : PlaygroundBackend(engine, mapOf(PATH to SAMPLE)) {
    override val project: ProjectInfo = ProjectInfo("Playground", "/project", moduleCount = 1)

    // A value above zero is what puts the shell in a project, not at the project picker.
    override val projectEpoch: StateFlow<Int> = MutableStateFlow(1)

    override fun fileTree(mode: TreeViewMode): TreeNode = TreeNode(
        id = "project", name = "Playground", kind = NodeKind.Workspace, filePath = null,
        children = documents.keys.sorted().map { path ->
            TreeNode(id = "file:$path", name = path.substringAfterLast('/'), kind = NodeKind.File, filePath = path, iconId = "kotlin")
        },
    )

    override fun saveFile(path: String, text: String) { documents[path] = text }

    // What the shell restores when it opens the project: Main.kt, in a tab and active, so the Playground lands on
    // code, not on the empty "No file open" screen.
    override fun openTabs(): UiOpenTabs = UiOpenTabs.ofPaths(listOf(PATH), activeIndex = 0)
    override fun hasSavedSession(): Boolean = true

    private val reanalysis = MutableStateFlow(0)

    /** The shell re-runs its analysis passes when this moves: bumped when the engine comes up, so the first colours and hints do not wait for a key. */
    override val reanalysisRequests: StateFlow<Int> get() = reanalysis

    fun engineReady() { reanalysis.value = reanalysis.value + 1 }

    // ---- Run: the shell's own Run button, its console and its Stop, over the engine -----------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(BuildState())
    private val console = MutableStateFlow<RunConsoleUi?>(null)
    private var runId = 0

    override val buildState: StateFlow<BuildState> = state
    override val runConsole: StateFlow<RunConsoleUi?> get() = console
    override fun supported(): Boolean = true

    // The shell's Run button opens this picker; with one task it is one tap.
    override fun runTasks(): List<RunTaskOption> = listOf(RunTaskOption("run:main", "Run Main.kt", "run"))
    override fun runTask(id: String) = runBuild()

    override fun runBuild() {
        val id = ++runId
        val source = documents[PATH].orEmpty()
        state.value = BuildState(status = RunStatus.Running, moduleName = "Playground")
        console.value = RunConsoleUi(id, "Playground", "MainKt", phase = RunPhase.Running)
        scope.launch {
            val r = engine.run(source)
            if (runId != id) return@launch // stopped, or a newer run took over
            val failed = r.error.isNotEmpty()
            val transcript = buildList {
                if (r.stdout.isNotEmpty()) add(ConsoleChunk(r.stdout, ConsoleChunkKind.OUTPUT))
                if (failed) add(ConsoleChunk((if (r.stdout.isEmpty() || r.stdout.endsWith("\n")) "" else "\n") + r.error + "\n", ConsoleChunkKind.SYSTEM))
            }
            console.value = RunConsoleUi(id, "Playground", "MainKt", phase = RunPhase.Finished, transcript = transcript, exitCode = if (failed) 1 else 0)
            state.value = BuildState(
                status = if (failed) RunStatus.Failed else RunStatus.Succeeded,
                moduleName = "Playground",
                log = listOf(BuildLogLine("analysis ${r.timing.substringBefore('/')} ms, run ${r.timing.substringAfter('/')} ms")),
            )
        }
    }

    override fun stopBuild() {
        val id = ++runId
        engine.stop() // the interpreter cannot be interrupted from outside: throw the worker away and start another
        console.value = RunConsoleUi(id, "Playground", "MainKt", phase = RunPhase.Finished,
            transcript = listOf(ConsoleChunk("Stopped.\n", ConsoleChunkKind.SYSTEM)), exitCode = 143)
        state.value = BuildState(status = RunStatus.Idle, moduleName = "Playground")
    }
}

/** The whole IDE shell over the Playground engine. */
@Composable
fun PlaygroundIdeApp() {
    var backendRef: PlaygroundIdeBackend? = null
    val engine = remember { EngineClient(onStatus = {}, onReady = { backendRef?.engineReady() }) }
    val backend = remember { PlaygroundIdeBackend(engine).also { backendRef = it } }
    LaunchedEffect(Unit) { engine.start() }
    CodeAssistApp(backend = backend)
}
