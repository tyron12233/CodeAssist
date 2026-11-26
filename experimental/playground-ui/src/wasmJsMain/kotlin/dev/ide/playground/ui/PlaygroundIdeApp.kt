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
import dev.ide.ui.backend.UiDirEntry
import dev.ide.ui.backend.UiNewFileTemplate
import dev.ide.ui.backend.UiOpenTabs
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.TreeViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The Playground as an IDE project: one open project, `/project`, of any number of Kotlin files in any folders, so the
 * whole desktop shell (file tree, tabs, toolbar, status bar, settings) has a project to show, and the editor inside
 * it gets its completion, diagnostics, colours and the rest from the engine worker through [PlaygroundBackend].
 *
 * The files live here, in memory, and every change is pushed to the engine so its language service sees all of them:
 * a run starts at the `fun main()` that is in one of them and follows the program into the others.
 */
class PlaygroundIdeBackend(engine: EngineClient) : PlaygroundBackend(engine, mapOf(PATH to SAMPLE)) {
    override val project: ProjectInfo = ProjectInfo("Playground", ROOT, moduleCount = 1)

    // A value above zero is what puts the shell in a project, not at the project picker.
    override val projectEpoch: StateFlow<Int> = MutableStateFlow(1)

    // ---- the project's files --------------------------------------------------------------------------------

    /** Folders that exist although no file is in them yet (a new folder); a folder with files is implied by them. */
    private val emptyDirs = linkedSetOf<String>()
    private val fsEpoch = MutableStateFlow(0)

    /** The shell refreshes its tree when this moves. */
    override val fileSystemEpoch: StateFlow<Int> get() = fsEpoch

    private fun changed() { fsEpoch.value = fsEpoch.value + 1 }

    private fun parentOf(path: String) = path.substringBeforeLast('/', ROOT).ifEmpty { ROOT }
    private fun join(dir: String, name: String) = "${dir.trimEnd('/')}/${name.trim('/')}"
    private fun isUnder(path: String, dir: String) = path.startsWith("${dir.trimEnd('/')}/")

    /** Every folder of the project below the root: the empty ones, and every ancestor of a file. */
    private fun allDirs(): Set<String> {
        val dirs = LinkedHashSet<String>(emptyDirs)
        for (file in documents.keys) {
            var d = parentOf(file)
            while (d != ROOT && d.isNotEmpty() && dirs.add(d)) d = parentOf(d)
        }
        return dirs
    }

    private fun fileNode(path: String) = TreeNode(
        id = "file:$path", name = path.substringAfterLast('/'), kind = NodeKind.File, filePath = path,
        iconId = if (path.endsWith(".kt")) "kotlin" else "file", dirPath = parentOf(path),
    )

    override fun fileTree(mode: TreeViewMode): TreeNode {
        val dirs = allDirs()
        fun children(dir: String): List<TreeNode> =
            dirs.filter { parentOf(it) == dir }.sorted().map { d ->
                TreeNode(id = "dir:$d", name = d.substringAfterLast('/'), kind = NodeKind.Folder, filePath = null, iconId = "folder", children = children(d), dirPath = d)
            } + documents.keys.filter { parentOf(it) == dir }.sorted().map(::fileNode)
        // `dirPath` is where the shell creates a new file or folder: the directory a node stands for.
        return TreeNode(id = "project", name = "Playground", kind = NodeKind.Workspace, filePath = null, children = children(ROOT), dirPath = ROOT)
    }

    override fun listDirectory(dirPath: String): List<UiDirEntry> {
        val dir = dirPath.trimEnd('/').ifEmpty { ROOT }
        return allDirs().filter { parentOf(it) == dir }.sorted().map { UiDirEntry(it.substringAfterLast('/'), it, isDirectory = true, iconId = "folder") } +
            documents.keys.filter { parentOf(it) == dir }.sorted().map { UiDirEntry(it.substringAfterLast('/'), it, isDirectory = false) }
    }

    /** The package a file in [dir] declares: its folders below the root, dotted. */
    private fun packageFor(dir: String): String = dir.removePrefix(ROOT).trim('/').replace('/', '.')

    private fun scaffold(dir: String, fileName: String, body: String = ""): String {
        val pkg = packageFor(dir)
        return (if (pkg.isEmpty()) "" else "package $pkg\n\n") + body
    }

    private fun add(path: String, text: String): String? {
        if (path in documents) return null
        documents[path] = text
        engine.put(path, text)
        changed()
        return path
    }

    override fun createFile(dirPath: String, fileName: String, content: String): String? = add(join(dirPath, fileName), content)

    // [name] may carry folders (`util/Strings.kt`); the content is scaffolded from where the file lands.
    override fun createFileSmart(dirPath: String, name: String): String? {
        val path = join(dirPath, name)
        return add(path, if (path.endsWith(".kt")) scaffold(parentOf(path), path.substringAfterLast('/')) else "")
    }

    override fun createSourceFile(dirPath: String, name: String, template: UiNewFileTemplate): String? {
        val type = name.removeSuffix(".kt")
        val body = when (template) {
            UiNewFileTemplate.KotlinFile -> ""
            UiNewFileTemplate.KotlinClass -> "class $type\n"
            UiNewFileTemplate.KotlinInterface -> "interface $type\n"
            UiNewFileTemplate.KotlinDataClass -> "data class $type(val value: Int)\n"
            UiNewFileTemplate.KotlinEnum -> "enum class $type {\n    A, B\n}\n"
            UiNewFileTemplate.KotlinObject -> "object $type\n"
            else -> return null // Java and AIDL have no backend here
        }
        return add(join(dirPath, "$type.kt"), scaffold(dirPath, name, body))
    }

    override fun createDirectory(parentPath: String, name: String): String? {
        val path = join(parentPath, name)
        if (path in allDirs() || path in documents) return null
        emptyDirs += path
        changed()
        return path
    }

    override fun deletePath(path: String): Boolean {
        val gone = documents.keys.filter { it == path || isUnder(it, path) }
        val goneDirs = emptyDirs.filter { it == path || isUnder(it, path) }
        if (gone.isEmpty() && goneDirs.isEmpty()) return false
        gone.forEach { documents.remove(it); engine.remove(it) }
        emptyDirs.removeAll(goneDirs.toSet())
        changed()
        return true
    }

    /** Move [path] (a file or a folder with everything in it) to [target]; the new path, or null if something is there. */
    private fun relocate(path: String, target: String, copy: Boolean): String? {
        if (target == path || target in documents || target in allDirs()) return null
        val moving = documents.keys.filter { it == path || isUnder(it, path) }
        val dirs = emptyDirs.filter { it == path || isUnder(it, path) }
        if (moving.isEmpty() && dirs.isEmpty()) return null
        for (old in moving) {
            val new = target + old.removePrefix(path)
            val text = documents[old].orEmpty()
            if (!copy) { documents.remove(old); engine.remove(old) }
            documents[new] = text
            engine.put(new, text)
        }
        for (old in dirs) {
            if (!copy) emptyDirs.remove(old)
            emptyDirs += target + old.removePrefix(path)
        }
        changed()
        return target
    }

    override suspend fun renamePath(path: String, newName: String): UiRenameResult {
        val target = join(parentOf(path), newName)
        val moved = relocate(path, target, copy = false) ?: return UiRenameResult(false, "$newName already exists, or there is nothing to rename.")
        return UiRenameResult(true, "", filesChanged = 1, newPath = moved)
    }

    override fun movePath(path: String, destDir: String): String? = relocate(path, join(destDir, path.substringAfterLast('/')), copy = false)
    override fun copyPath(path: String, destDir: String): String? = relocate(path, join(destDir, path.substringAfterLast('/')), copy = true)

    override fun saveFile(path: String, text: String) {
        documents[path] = text
        engine.put(path, text)
    }

    override fun updateDocument(path: String, text: String) {
        super.updateDocument(path, text)
        engine.put(path, text) // the other files' language service reads this file from the project, not from the editor
    }

    // What the shell restores when it opens the project: Main.kt, in a tab and active, so the Playground lands on
    // code, not on the empty "No file open" screen.
    override fun openTabs(): UiOpenTabs = UiOpenTabs.ofPaths(listOf(PATH), activeIndex = 0)
    override fun hasSavedSession(): Boolean = true

    private val reanalysis = MutableStateFlow(0)

    /** The shell re-runs its analysis passes when this moves: bumped when the engine comes up, so the first colours and hints do not wait for a key. */
    override val reanalysisRequests: StateFlow<Int> get() = reanalysis

    /** The engine is up (or came back after a Stop): give it the whole project, then have the shell analyse again. */
    fun engineReady() {
        documents.forEach { (path, text) -> engine.put(path, text) }
        reanalysis.value = reanalysis.value + 1
    }

    // ---- Run: the shell's own Run button, its console and its Stop, over the engine -----------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow(BuildState())
    private val console = MutableStateFlow<RunConsoleUi?>(null)
    private var runId = 0

    override val buildState: StateFlow<BuildState> = state
    override val runConsole: StateFlow<RunConsoleUi?> get() = console
    override fun supported(): Boolean = true

    /** The file whose `fun main()` a run starts at: `Main.kt` when it has one, else the first file that does. */
    private fun entryPath(): String? {
        val withMain = documents.filterValues { MAIN.containsMatchIn(it) }.keys
        return withMain.firstOrNull { it == PATH } ?: withMain.sorted().firstOrNull()
    }

    // The shell's Run button opens this picker; with one task it is one tap.
    override fun runTasks(): List<RunTaskOption> =
        listOf(RunTaskOption("run:main", "Run ${entryPath()?.substringAfterLast('/') ?: "main"}", "run"))
    override fun runTask(id: String) = runBuild()

    override fun runBuild() {
        val id = ++runId
        val entry = entryPath()
        if (entry == null) {
            console.value = RunConsoleUi(id, "Playground", "main", phase = RunPhase.Finished, exitCode = 1,
                transcript = listOf(ConsoleChunk("No `fun main()` in the project.\n", ConsoleChunkKind.SYSTEM)))
            state.value = BuildState(status = RunStatus.Failed, moduleName = "Playground")
            return
        }
        val source = documents[entry].orEmpty()
        val mainClass = entry.substringAfterLast('/').removeSuffix(".kt") + "Kt"
        state.value = BuildState(status = RunStatus.Running, moduleName = "Playground")
        console.value = RunConsoleUi(id, "Playground", mainClass, phase = RunPhase.Running)
        scope.launch {
            val r = engine.run(entry, source)
            if (runId != id) return@launch // stopped, or a newer run took over
            val failed = r.error.isNotEmpty()
            val transcript = buildList {
                if (r.stdout.isNotEmpty()) add(ConsoleChunk(r.stdout, ConsoleChunkKind.OUTPUT))
                if (failed) add(ConsoleChunk((if (r.stdout.isEmpty() || r.stdout.endsWith("\n")) "" else "\n") + r.error + "\n", ConsoleChunkKind.SYSTEM))
            }
            console.value = RunConsoleUi(id, "Playground", mainClass, phase = RunPhase.Finished, transcript = transcript, exitCode = if (failed) 1 else 0)
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
        console.value = RunConsoleUi(id, "Playground", "main", phase = RunPhase.Finished,
            transcript = listOf(ConsoleChunk("Stopped.\n", ConsoleChunkKind.SYSTEM)), exitCode = 143)
        state.value = BuildState(status = RunStatus.Idle, moduleName = "Playground")
    }

    private companion object {
        const val ROOT = "/project"
        val MAIN = Regex("""\bfun\s+main\s*\(""")
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
