package dev.ide.ios

import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSProcessInfo
import kotlin.time.TimeSource

/**
 * A walk through the backend the app was started with, for a simulator: nothing there can tap the screen, so
 * the app is asked by its environment to do what a person would and to write down what came of it.
 *
 * `CODEASSIST_SELFCHECK` names the steps, comma separated (`simctl launch` passes it as
 * `SIMCTL_CHILD_CODEASSIST_SELFCHECK`): `skip-intro` marks the first-launch sheets seen; `create:<template>`
 * makes and opens a project from a template (the app follows it into the editor, as it does for a person);
 * `open:<name>` opens one that exists; `edit` asks for diagnostics and a completion in its first source file;
 * `build` runs the default build to its end. Each step's outcome and time goes to `Documents/selfcheck.log`.
 * Without the variable this does nothing.
 */
internal object IosSelfCheck {
    fun start(backend: IdeBackend, projectsRoot: String, scope: CoroutineScope) {
        val steps = NSProcessInfo.processInfo.environment["CODEASSIST_SELFCHECK"] as? String ?: return
        val log = IosFiles.join(IosFiles.documentsDir(), "selfcheck.log")
        val lines = StringBuilder()
        fun say(line: String) { lines.append(line).append('\n'); IosFiles.writeText(log, lines.toString()) }
        say("backend: ${backend::class.simpleName}")
        scope.launch {
            for (step in steps.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val started = TimeSource.Monotonic.markNow()
                val outcome = runCatching { run(step, backend, projectsRoot) }.getOrElse { "FAILED ${it::class.simpleName}: ${it.message}" }
                say("$step: $outcome (${started.elapsedNow().inWholeMilliseconds} ms)")
            }
            say("done")
        }
    }

    private val launched = TimeSource.Monotonic.markNow()

    private suspend fun run(step: String, backend: IdeBackend, projectsRoot: String): String {
        val argument = step.substringAfter(':', "")
        // The app reopens the project it was last in as it starts. A project this walk opens in the same moment
        // would be replaced by that one, so it lets the app finish first.
        if (step.startsWith("create") || step.startsWith("open")) delay((4000 - launched.elapsedNow().inWholeMilliseconds).coerceAtLeast(0))
        return when (step.substringBefore(':')) {
            "skip-intro" -> {
                for (key in listOf("onboarding.seen", "migration.acknowledged", "legacy.recovery.seen")) backend.settings.setPreference(key, "true")
                "seen"
            }
            "templates" -> backend.projects.projectTemplates().joinToString { it.id }
            "create" -> {
                val before = backend.projects.projectEpoch.value
                val name = "Check" + argument.filter { it.isLetterOrDigit() }.take(16)
                val folder = IosFiles.join(projectsRoot, name.lowercase())
                if (IosFiles.exists(folder)) backend.projects.deleteProject(folder)
                val made = backend.projects.createProject(argument, mapOf("name" to name, "packageName" to "com.example.${name.lowercase()}"))
                if (!made.success) return "FAILED ${made.message}"
                withTimeoutOrNull(60_000) { backend.projects.projectEpoch.first { it != before } } ?: return "FAILED the project never became the open one"
                // What the editor screen does as it is entered, which a project made from the picker always is.
                backend.deps.startPendingDependencyResolution()
                "opened ${backend.project.name} at ${backend.project.rootPath}, ${backend.project.moduleCount} module(s)"
            }
            "deps" -> {
                delay(1500)
                val state = withTimeoutOrNull(600_000) { backend.deps.depsState.first { !it.resolving } }
                    ?: return "FAILED still resolving: ${backend.deps.depsState.value.message}"
                "resolved; ${state.message} ${state.log.takeLast(3)}"
            }
            "open" -> {
                val before = backend.projects.projectEpoch.value
                if (!backend.projects.openProject(IosFiles.join(projectsRoot, argument))) return "FAILED it would not open"
                withTimeoutOrNull(60_000) { backend.projects.projectEpoch.first { it != before } }
                "opened ${backend.project.name}"
            }
            "tabs" -> {
                fun TreeNode.all(): List<TreeNode> = listOf(this) + children.flatMap { it.all() }
                val path = backend.files.fileTree(TreeViewMode.Project).all().mapNotNull { it.filePath }
                    .firstOrNull { it.endsWith(".kt") || it.endsWith(".java") } ?: return "FAILED no source file in the tree"
                // `tabs:preview` leaves the tab on its preview, which is then what the next launch shows.
                val tab = dev.ide.ui.backend.UiOpenTab(path, viewMode = argument.ifEmpty { "text" })
                backend.projects.saveOpenTabs(dev.ide.ui.backend.UiOpenTabs(listOf(tab), 0))
                "saved ${path.substringAfterLast('/')} as the open tab"
            }
            "bench" -> {
                val calls = (backend as? EngineBackend)?.calls ?: return "not an engine backend"
                val mark = TimeSource.Monotonic.markNow()
                repeat(2000) { backend.settings.preference("selfcheck.bench") }
                val each = mark.elapsedNow().inWholeMicroseconds / 2000
                val text = "x".repeat(100_000)
                val big = TimeSource.Monotonic.markNow()
                repeat(50) { backend.editor.updateDocument("/selfcheck/bench.txt", text) }
                val document = big.elapsedNow().inWholeMicroseconds / 50
                val carried = TimeSource.Monotonic.markNow()
                repeat(50) { backend.files.moduleNameForFile(text) }
                "$each us a small call, ${carried.elapsedNow().inWholeMicroseconds / 50} us carrying 100 KB, $document us for a 100 KB document"
            }
            "trace" -> {
                val calls = (backend as? EngineBackend)?.calls ?: return "not an engine backend"
                // `trace:on` starts counting and goes on to the next step; `trace:<seconds>` waits, then reports.
                if (argument == "on") { calls.traced(); calls.tracing = true; return "counting" }
                if (!calls.tracing) { calls.traced(); calls.tracing = true }
                val seconds = argument.toLongOrNull() ?: 10
                delay(seconds * 1000)
                calls.tracing = false
                val all = calls.traced()
                "${all.sumOf { it.calls }} calls taking ${all.sumOf { it.micros } / 1000} ms in $seconds s; on the UI thread " +
                    "${all.sumOf { it.uiCalls }} taking ${all.sumOf { it.uiMicros } / 1000} ms" +
                    all.take(40).joinToString("") { "\n    ${it.service}/${it.member}: ${it.calls} calls ${it.micros / 1000} ms, UI ${it.uiCalls} calls ${it.uiMicros / 1000} ms" }
            }
            "wait" -> { delay((argument.toLongOrNull() ?: 10) * 1000); "waited" }
            "tree" -> {
                fun TreeNode.render(depth: Int): String = "  ".repeat(depth) + name + "\n" + children.joinToString("") { it.render(depth + 1) }
                "\n" + backend.files.fileTree(TreeViewMode.Project).render(1).trimEnd()
            }
            "edit" -> {
                fun TreeNode.all(): List<TreeNode> = listOf(this) + children.flatMap { it.all() }
                val path = backend.files.fileTree(TreeViewMode.Project).all().mapNotNull { it.filePath }
                    .firstOrNull { it.endsWith(".kt") || it.endsWith(".java") } ?: return "FAILED no source file in the tree"
                val text = IosFiles.readText(path)
                backend.editor.updateDocument(path, text)
                // The index is built as the project opens; what is offered before it is ready is not the answer.
                withTimeoutOrNull(120_000) { backend.search.indexStatus.first { !it.building } }
                val diagnostics = backend.editor.analyze(path, text)
                val at = text.lastIndexOf('}').coerceAtLeast(0)
                val probe = if (path.endsWith(".kt")) "\nval probe = \"\".sub" else "\nObject probe = \"\".sub"
                val typed = text.substring(0, at) + probe + "\n" + text.substring(at)
                val offered = backend.editor.complete(path, typed, at + probe.length)
                // The engine writes its buffers out before a build: it is left holding the file as it is on disk.
                backend.editor.updateDocument(path, text)
                "${path.substringAfterLast('/')}: ${diagnostics.size} diagnostic(s) ${diagnostics.take(3).map { it.message }}, completion ${offered.items.take(5).map { it.label }}"
            }
            "build" -> {
                backend.build.runBuild()
                // A build that has nothing to do is over before its state is next read: what is waited for is an end.
                val finished = withTimeoutOrNull(900_000) {
                    backend.build.buildState.first { it.status != RunStatus.Idle && it.status != RunStatus.Running }
                } ?: return "FAILED the build never finished (${backend.build.buildState.value.status})"
                IosFiles.writeText(IosFiles.join(IosFiles.documentsDir(), "selfcheck-build.log"), finished.log.joinToString("\n") { "${it.level} ${it.message}" })
                val errors = finished.log.filter { it.level == dev.ide.ui.backend.UiLogLevel.Error }.map { it.message } +
                    finished.diagnostics.map { it.toString() }
                "${finished.status}, ${finished.log.size} lines, last: ${finished.log.takeLast(2).map { it.message }}" +
                    errors.take(12).joinToString("") { "\n    $it" }
            }
            "dep" -> {
                // `dep:<group:artifact:version>` adds an implementation dependency to the `app` module.
                val added = backend.deps.addDependency("app", argument, "implementation")
                delay(1500)
                withTimeoutOrNull(600_000) { backend.deps.depsState.first { !it.resolving } }
                "$added"
            }
            "program" -> {
                // `program:<name>` replaces the open project's Main.kt with one of [PROGRAMS] and runs it through the
                // project's first run task, reporting what the console printed.
                val program = PROGRAMS[argument] ?: return "FAILED no program named '$argument' (${PROGRAMS.keys})"
                fun TreeNode.all(): List<TreeNode> = listOf(this) + children.flatMap { it.all() }
                val main = backend.files.fileTree(TreeViewMode.Project).all().mapNotNull { it.filePath }
                    .firstOrNull { it.endsWith("/Main.kt") } ?: return "FAILED no Main.kt in the tree"
                val packageLine = IosFiles.readText(main).lineSequence().firstOrNull { it.startsWith("package ") }
                IosFiles.writeText(main, listOfNotNull(packageLine, program).joinToString("\n\n"))
                backend.editor.updateDocument(main, IosFiles.readText(main))
                val task = backend.build.runTasks().firstOrNull { it.group.contains("run", ignoreCase = true) || it.id.startsWith("run") }
                    ?: return "FAILED no run task in ${backend.build.runTasks().map { it.id }}"
                backend.build.runTask(task.id)
                val done = withTimeoutOrNull(600_000) {
                    backend.build.runConsole.first { it != null && it.phase == dev.ide.ui.backend.RunPhase.Finished }
                } ?: return "FAILED the run never finished: ${backend.build.runConsole.value?.transcript?.joinToString("") { it.text }?.takeLast(800)}"
                "exit ${done.exitCode} via ${task.id}\n" + done.transcript.joinToString("") { it.text }.trimEnd().prependIndent("    ")
            }
            "challenge" -> {
                // What opening today's challenge waits on, one call at a time: `challenge:kotlin` for the other language.
                val language = if (argument == "kotlin") dev.ide.ui.backend.UiChallengeLanguage.Kotlin else dev.ide.ui.backend.UiChallengeLanguage.Java
                val parts = mutableListOf<String>()
                suspend fun <T> timed(label: String, block: suspend () -> T): T {
                    val at = TimeSource.Monotonic.markNow()
                    return block().also { parts += "$label ${at.elapsedNow().inWholeMilliseconds} ms" }
                }
                if (!timed("available") { backend.challenges.challengesAvailable() }) return "FAILED challenges not available"
                val day = timed("day") { backend.challenges.day(null) }
                val problem = day.problem ?: return "FAILED no problem (${day.reason}) ${parts.joinToString()}"
                val ready = timed("prepare") { backend.challenges.prepare(language) }
                val stub = problem.materials[language]?.stub.orEmpty()
                val diagnostics = timed("analyze") { backend.challenges.analyze(language, stub) }
                "${problem.title}: ready=$ready, ${diagnostics.size} diagnostic(s); " + parts.joinToString()
            }
            else -> "unknown step"
        }
    }
}

/** What `program:<name>` runs: each one leans on a different way user code meets the runtime. */
private val PROGRAMS = mapOf(
    "plain" to """
        fun main() {
            val words = listOf("winter", "is", "here")
            println("plain: " + words.joinToString(" ") { it.uppercase() } + " " + words.sumOf { it.length })
        }
    """.trimIndent(),
    "exception" to """
        class Grumpy(message: String) : Exception(message)
        class Cranky : IllegalStateException("cranky")

        fun main() {
            try { throw Grumpy("grumpy") } catch (e: Grumpy) { println("caught " + e.message) }
            try { throw Cranky() } catch (e: IllegalStateException) { println("caught " + e.message) }
            val r = runCatching { error("boom") }
            println("runCatching " + r.exceptionOrNull()?.message)
        }
    """.trimIndent(),
    "thread" to """
        fun main() {
            val t = object : Thread() { override fun run() { println("thread ran") } }
            t.start(); t.join()
            val r = Thread { println("runnable ran") }
            r.start(); r.join()
        }
    """.trimIndent(),
    "coroutines" to """
        import kotlinx.coroutines.*

        suspend fun twice(x: Int): Int { delay(10); return x * 2 }

        fun main() = runBlocking {
            println("Start")
            delay(100)
            val a = async { twice(21) }
            launch { println("launched") }
            println("Done " + a.await())
        }
    """.trimIndent(),
)
