package dev.ide.buildcli

import dev.ide.core.headless.HeadlessBuildResult
import dev.ide.core.headless.HeadlessEngine
import dev.ide.ui.backend.UiLogLevel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `codeassist` — build a CodeAssist project from a terminal or from CI, with the IDE's own build system.
 *
 *     codeassist assemble --project . --variant debug
 *     codeassist tasks --project .
 *
 * The build runs exactly as it does on a phone (resolve → aapt2 → javac/K2 → D8 → sign); this is only the
 * host around it. Inside GitHub Actions it additionally emits workflow commands, so compiler and resource
 * errors land on the pull request's diff instead of only in the log. See docs/github-action.md.
 */
fun main(args: Array<String>) {
    val code = try {
        dispatch(args)
    } catch (e: UsageError) {
        System.err.println("codeassist: ${e.message}")
        System.err.println("Try 'codeassist --help'.")
        EXIT_USAGE
    } catch (e: Throwable) {
        System.err.println("codeassist: the build host failed: ${e.message}")
        e.printStackTrace()
        EXIT_USAGE
    }
    // The engine leaves pools and watch threads behind, so returning from main() would hang. Every path
    // through the build has already reported its outcome by here.
    exitProcess(code)
}

/** 0: the build succeeded. 1: it ran and failed. 2: it never ran (bad arguments, no such task). */
private const val EXIT_OK = 0
private const val EXIT_BUILD_FAILED = 1
private const val EXIT_USAGE = 2

private class UsageError(message: String) : RuntimeException(message)

private fun dispatch(args: Array<String>): Int {
    if (args.any { it == "-h" || it == "--help" }) {
        println(USAGE)
        return EXIT_OK
    }
    if (args.any { it == "--version" }) {
        println(version())
        return EXIT_OK
    }
    val command = args.firstOrNull()?.takeUnless { it.startsWith("-") } ?: "assemble"
    val rest = if (args.firstOrNull() == command) args.drop(1) else args.toList()
    val options = Options.parse(rest)
    return when (command) {
        "assemble", "build" -> assemble(options)
        "tasks" -> listTasks(options)
        "import" -> importProject(options)
        "create" -> create(options)
        "templates" -> listTemplates()
        else -> throw UsageError("unknown command '$command'")
    }
}

private fun assemble(options: Options): Int {
    val report = Reporter(options)
    openEngine(options).use { engine ->
        val taskId = options.taskId
            ?: engine.assembleTaskFor(options.variant, options.module)
            ?: return report.noSuchTask(engine)

        report.start(taskId)
        val unresolved = runBlocking { engine.resolveDependencies(options.resolveTimeoutMs) }
        if (unresolved.isNotEmpty()) {
            report.unresolved(unresolved)
            return EXIT_BUILD_FAILED
        }
        val result = runBlocking { engine.build(taskId, options.buildTimeoutMs) { report.logLine(it) } }

        report.finish(result)
        return if (result.succeeded) EXIT_OK else EXIT_BUILD_FAILED
    }
}

/**
 * Import a foreign build system's project into a CodeAssist workspace, then report what it can build.
 *
 * The case this exists for is a Gradle repository: run its `generateNativeModel` task (which writes
 * `.platform/gradle-model.json` from the model Gradle actually configured), then `codeassist import`, and
 * the directory is a CodeAssist project every other command accepts. Like `create`, it does not require a
 * workspace to exist; unlike `create`, it is safe to re-run, which is how a dependency change in the Gradle
 * build reaches the workspace.
 */
private fun importProject(options: Options): Int {
    if (!Files.isDirectory(options.project)) throw UsageError("no such directory: ${options.project}")
    val existed = HeadlessEngine.isWorkspace(options.project)
    if (!HeadlessEngine.importProject(options.project)) {
        throw UsageError(
            "nothing in ${options.project} could be imported. A Gradle project needs its exported model " +
                "first: ./gradlew generateNativeModel"
        )
    }
    println(if (existed) "Refreshed the workspace in ${options.project}" else "Imported ${options.project}")
    openEngine(options).use { engine ->
        println("Modules: ${engine.moduleNames().size}")
        engine.tasks().take(TASK_PREVIEW).forEach { println("  ${it.id}") }
        val more = engine.tasks().size - TASK_PREVIEW
        if (more > 0) println("  ... and $more more (codeassist tasks)")
    }
    return EXIT_OK
}

/** How many task ids `import` echoes before pointing at `codeassist tasks` for the rest. */
private const val TASK_PREVIEW = 10

/**
 * Scaffold a project from a template into `--project`, then report what it can build. Creation writes the
 * workspace the other commands need, so this is the one command that does not require one to exist.
 */
private fun create(options: Options): Int {
    val template = options.template ?: throw UsageError("create needs --template (see `codeassist templates`)")
    if (HeadlessEngine.isWorkspace(options.project)) {
        throw UsageError("${options.project} already holds a CodeAssist project")
    }
    val name = options.name ?: options.project.fileName?.toString() ?: "Untitled"
    val args = mapOf("name" to name, "packageName" to options.packageName) + options.templateArgs
    HeadlessEngine.create(options.project, template, args, options.cacheDir).use { engine ->
        println("Created '$name' from '$template' in ${options.project}")
        engine.tasks().forEach { println("  ${it.id}") }
    }
    return EXIT_OK
}

private fun listTemplates(): Int {
    val templates = HeadlessEngine.templates()
    val width = templates.maxOfOrNull { it.first.length } ?: 0
    templates.forEach { (id, displayName) -> println("${id.padEnd(width)}  $displayName") }
    return EXIT_OK
}

private fun listTasks(options: Options): Int {
    openEngine(options).use { engine ->
        val tasks = engine.tasks()
        if (tasks.isEmpty()) {
            println("No tasks. Modules: ${engine.moduleNames().ifEmpty { listOf("(none)") }.joinToString(", ")}")
            return EXIT_USAGE
        }
        val width = tasks.maxOf { it.id.length }
        for (task in tasks) println("${task.id.padEnd(width)}  ${task.label}")
        return EXIT_OK
    }
}

private fun openEngine(options: Options): HeadlessEngine {
    if (!Files.isDirectory(options.project)) throw UsageError("no such directory: ${options.project}")
    if (!HeadlessEngine.isWorkspace(options.project)) {
        val hint = if (Files.exists(options.project.resolve("settings.gradle")) ||
            Files.exists(options.project.resolve("settings.gradle.kts"))
        ) " It looks like a Gradle project: open it in CodeAssist once (which imports it) and commit the" +
            " .platform directory it writes."
        else ""
        throw UsageError("${options.project} is not a CodeAssist project (no .platform/workspace.json).$hint")
    }
    return HeadlessEngine.open(options.project, options.cacheDir)
}

/** Parsed command line. Defaults are the ones a CI job wants: this directory, the debug variant. */
private class Options(
    val project: Path,
    val variant: String,
    val module: String?,
    val taskId: String?,
    val cacheDir: Path?,
    val reportFile: Path?,
    val template: String?,
    val name: String?,
    val packageName: String,
    val templateArgs: Map<String, String>,
    val annotations: Boolean,
    val quiet: Boolean,
    val resolveTimeoutMs: Long,
    val buildTimeoutMs: Long,
) {
    companion object {
        fun parse(args: List<String>): Options {
            var project = Path.of("").toAbsolutePath()
            var variant = "debug"
            var module: String? = null
            var taskId: String? = null
            var cacheDir: Path? = null
            var reportFile: Path? = null
            var template: String? = null
            var name: String? = null
            var packageName = "com.example.app"
            val templateArgs = LinkedHashMap<String, String>()
            // Inside Actions the workflow commands are what makes a failure readable, so default them on
            // there and off everywhere else (they are noise in a terminal).
            var annotations = System.getenv("GITHUB_ACTIONS") == "true"
            var quiet = false
            var resolveMinutes = HeadlessEngine.DEFAULT_RESOLVE_TIMEOUT_MS / 60_000
            var buildMinutes = HeadlessEngine.DEFAULT_BUILD_TIMEOUT_MS / 60_000

            var i = 0
            fun value(flag: String): String =
                args.getOrNull(++i) ?: throw UsageError("$flag needs a value")
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--project", "-p" -> project = Path.of(value(arg)).toAbsolutePath().normalize()
                    "--variant", "-v" -> variant = value(arg)
                    "--module", "-m" -> module = value(arg)
                    "--task", "-t" -> taskId = value(arg)
                    "--cache" -> cacheDir = Path.of(value(arg)).toAbsolutePath().normalize()
                    "--report" -> reportFile = Path.of(value(arg)).toAbsolutePath().normalize()
                    "--template" -> template = value(arg)
                    "--name" -> name = value(arg)
                    "--package" -> packageName = value(arg)
                    "--annotations" -> annotations = true
                    "--no-annotations" -> annotations = false
                    "--quiet", "-q" -> quiet = true
                    "--resolve-timeout" -> resolveMinutes = minutes(arg, value(arg))
                    "--build-timeout" -> buildMinutes = minutes(arg, value(arg))
                    // `-Dkey=value` passes a template's own parameter through to it verbatim.
                    else -> if (arg.startsWith("-D") && arg.contains('=')) {
                        templateArgs[arg.removePrefix("-D").substringBefore('=')] = arg.substringAfter('=')
                    } else throw UsageError("unknown option '$arg'")
                }
                i++
            }
            return Options(
                project, variant, module, taskId, cacheDir, reportFile,
                template, name, packageName, templateArgs,
                annotations, quiet,
                resolveTimeoutMs = resolveMinutes * 60_000,
                buildTimeoutMs = buildMinutes * 60_000,
            )
        }

        private fun minutes(flag: String, raw: String): Long =
            raw.toLongOrNull()?.takeIf { it > 0 } ?: throw UsageError("$flag needs a positive number of minutes")
    }
}

/** Prints the build as it happens, and (in Actions) the workflow commands that annotate the diff. */
private class Reporter(private val options: Options) {

    private val github = GitHubActions(enabled = options.annotations, projectRoot = options.project)
    private val started = System.currentTimeMillis()

    fun start(taskId: String) {
        println("CodeAssist ${version()} — $taskId in ${options.project}")
        github.beginLogGroup("CodeAssist build log")
    }

    fun logLine(line: dev.ide.ui.backend.BuildLogLine) {
        if (options.quiet && line.level == UiLogLevel.Debug) return
        val stream = if (line.level == UiLogLevel.Error) System.err else System.out
        stream.println(line.message)
    }

    /** Dependencies that never resolved: the engine refuses the build, so say so in the same shape. */
    fun unresolved(unresolved: Map<String, List<String>>) {
        github.endLogGroup()
        System.err.println("Unresolved dependencies — the build cannot start:")
        unresolved.forEach { (module, coordinates) ->
            coordinates.forEach { System.err.println("  $module: $it") }
        }
        github.error(
            "Unresolved dependencies: " +
                unresolved.entries.joinToString("; ") { (m, c) -> "$m → ${c.joinToString(", ")}" }
        )
        github.summary(buildString {
            appendLine("## CodeAssist build: unresolved dependencies")
            appendLine()
            unresolved.forEach { (module, coordinates) ->
                coordinates.forEach { appendLine("- `$module` → `$it`") }
            }
        })
        github.output("status", "unresolved")
    }

    fun noSuchTask(engine: HeadlessEngine): Int {
        val wanted = options.module?.let { "assemble:$it:${options.variant}" }
            ?: "an assemble task for the '${options.variant}' variant"
        System.err.println("No $wanted in ${options.project}.")
        val tasks = engine.tasks()
        if (tasks.isEmpty()) System.err.println("This project offers no build tasks (modules: ${engine.moduleNames()}).")
        else {
            System.err.println("Available:")
            tasks.forEach { System.err.println("  ${it.id}") }
        }
        github.error("No $wanted in this project.")
        return EXIT_USAGE
    }

    fun finish(result: HeadlessBuildResult) {
        github.endLogGroup()
        val errors = result.diagnostics.filter { it.severity == dev.ide.ui.backend.UiSeverity.Error }
        val warnings = result.diagnostics.filter { it.severity == dev.ide.ui.backend.UiSeverity.Warning }
        github.annotate(result.diagnostics)

        val wall = System.currentTimeMillis() - started
        val verdict = when {
            result.succeeded -> "BUILD SUCCEEDED"
            result.timedOut -> "BUILD TIMED OUT"
            else -> "BUILD FAILED"
        }
        println()
        println("$verdict in ${format(result.elapsedMs.takeIf { it > 0 } ?: wall)}")
        if (errors.isNotEmpty() || warnings.isNotEmpty()) {
            println("${errors.size} error(s), ${warnings.size} warning(s)")
        }
        result.outputs.forEach { println("Output: $it") }
        if (!result.succeeded) {
            // The transcript can be thousands of lines; the errors are what the reader came for.
            val shown = errors.take(MAX_ERRORS_ECHOED)
            if (shown.isNotEmpty()) {
                System.err.println()
                shown.forEach { System.err.println("error: ${github.describe(it)}") }
                if (errors.size > shown.size) System.err.println("… and ${errors.size - shown.size} more")
            } else {
                result.log.filter { it.level == UiLogLevel.Error }.takeLast(MAX_ERRORS_ECHOED)
                    .forEach { System.err.println(it.message) }
            }
        }

        github.summarize(result, errors.size, warnings.size)
        github.output("status", if (result.succeeded) "success" else "failed")
        github.output("task", result.taskId)
        github.output("module", result.moduleName)
        github.output("elapsed-ms", result.elapsedMs.toString())
        github.output("errors", errors.size.toString())
        result.outputs.firstOrNull { it.toString().endsWith(".apk") }?.let { github.output("apk", it.toString()) }
        result.outputs.firstOrNull { it.toString().endsWith(".aab") }?.let { github.output("aab", it.toString()) }
        github.outputList("outputs", result.outputs.map { it.toString() })

        options.reportFile?.let { file ->
            Files.createDirectories(file.parent ?: file)
            Files.writeString(file, JsonReport.of(result, options.project))
            println("Report: $file")
        }
    }

    private fun format(ms: Long): String =
        if (ms < 60_000) "%.1fs".format(ms / 1000.0) else "%dm %ds".format(ms / 60_000, ms % 60_000 / 1000)

    private companion object {
        const val MAX_ERRORS_ECHOED = 50
    }
}

/** The version this launcher was built at (`Implementation-Version`), or `dev` for a local build. */
internal fun version(): String =
    Reporter::class.java.`package`?.implementationVersion ?: "dev"

private val USAGE = """
    codeassist — build a CodeAssist project with the IDE's own build system.

    Usage:
      codeassist [assemble] [options]   Build a variant and report its artifact (the default command)
      codeassist tasks [options]        List what this project can build
      codeassist import [options]       Import a Gradle project that exported its model, or refresh it
      codeassist create [options]       Scaffold a new project from a template
      codeassist templates              List the templates `create` accepts

    Options:
      -p, --project <dir>       Project directory (default: the current directory)
      -v, --variant <name>      Variant to assemble (default: debug)
      -m, --module <name>       Module to assemble (default: the first Android application module)
      -t, --task <id>           Run this exact task id instead (see `codeassist tasks`)
          --cache <dir>         Where downloaded dependencies are cached (default: inside the project)
          --report <file>       Write a JSON report of the build to this file
          --annotations         Emit GitHub Actions workflow commands (default: on inside Actions)
          --no-annotations      Never emit workflow commands
          --resolve-timeout <m> Minutes allowed for dependency resolution (default: 20)
          --build-timeout <m>   Minutes allowed for the build itself (default: 60)
          --template <id>       (create) Template to scaffold from, e.g. `android-app`
          --name <name>         (create) Project name (default: the directory's name)
          --package <pkg>       (create) Base package / Android namespace (default: com.example.app)
          -D<key>=<value>       (create) A template's own parameter, e.g. -DminSdk=24
      -q, --quiet               Drop debug-level log lines
      -h, --help                Show this message
          --version             Print the version

    Exit codes: 0 the build succeeded, 1 it ran and failed, 2 it never ran.

    The Android SDK is found through ANDROID_HOME, ANDROID_SDK_ROOT, the project's local.properties, or the
    default install location. Building an APK needs its build-tools (aapt2, d8, apksigner) and a platform.
""".trimIndent()
