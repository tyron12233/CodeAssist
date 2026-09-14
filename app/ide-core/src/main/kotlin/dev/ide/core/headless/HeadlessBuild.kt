package dev.ide.core.headless

import dev.ide.android.support.AndroidBuildSystem
import dev.ide.build.engine.buildDir
import dev.ide.core.ApplicationEnvironment
import dev.ide.core.IdeServices
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.ProjectTemplateRegistry
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.RunStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Drives a build with no UI attached: open a workspace, resolve its dependencies, run one assemble task,
 * and report what came out. The engine underneath is the one the IDE runs — [IdeServices] in `buildOnly`
 * mode, the same headless configuration the on-device `:build` daemon uses — so a build here takes exactly
 * the path a build on a phone takes (resolve → aapt2 → javac/K2 → D8 → sign).
 *
 * This exists because the build seams it needs ([IdeServices.openAt]'s SDK argument, the dependency
 * service) are `internal` to :ide-core, so a launcher outside the module cannot reach them. Everything
 * here is a thin pass-through; the behavior lives in `BuildService`.
 *
 * The engine holds native resources (compiler environments, index buffers, watch threads) — always
 * [close] it, or use it in a `use { }` block.
 */
class HeadlessEngine private constructor(private val ide: IdeServices) : AutoCloseable {

    /** The assemble/build/run tasks this workspace offers, in the order the Run picker would list them. */
    fun tasks(): List<HeadlessTask> =
        ide.buildRunner.runTasks().map { HeadlessTask(it.id, it.label, it.group) }

    /** The module names in the workspace, in model order. */
    fun moduleNames(): List<String> = ide.modules().map { it.name }

    /**
     * Pick the task to run from a [variant] (and an optional [module]) the way the Run picker's default
     * does: the Android `assemble:<module>:<variant>` for the named module, or the first app module's when
     * none is named. Null when the workspace offers no such task (no Android application module, or no
     * variant by that name), so a caller can report the real list instead of guessing.
     */
    fun assembleTaskFor(variant: String, module: String? = null): String? {
        val ids = tasks().map { it.id }
        if (module != null) return ids.firstOrNull { it == "assemble:$module:$variant" }
        return ids.firstOrNull { it.startsWith("assemble:") && it.endsWith(":$variant") }
    }

    /**
     * Resolve every declared dependency, returning the coordinates that did NOT resolve, keyed by module
     * (empty when everything resolved). A build with unresolved dependencies is refused by the engine
     * before it compiles anything, so a caller reports this rather than letting the build fail obscurely.
     *
     * Downloads land in the resolved-dependency cache under the shared caches root (see [open]), so a
     * second run with the same cache is offline.
     */
    suspend fun resolveDependencies(timeoutMs: Long = DEFAULT_RESOLVE_TIMEOUT_MS): Map<String, List<String>> {
        withTimeout(timeoutMs) { ide.dependencies.retryDependencyResolution() }
        return ide.modules()
            .associate { it.name to ide.dependencies.declaredUnresolved(it) }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Run [taskId] (an id from [tasks]) and wait for it to finish. [onLog] receives each transcript line
     * once, as it is produced, so a caller can stream the build rather than wait for the whole log.
     *
     * A build that outruns [timeoutMs] comes back as a failed result carrying the log so far, not an
     * exception: the caller's job is to report a build outcome, and a timeout is one.
     */
    suspend fun build(
        taskId: String,
        timeoutMs: Long = DEFAULT_BUILD_TIMEOUT_MS,
        onLog: (BuildLogLine) -> Unit = {},
    ): HeadlessBuildResult {
        val runner = ide.buildRunner
        var emitted = 0
        runner.runTask(taskId)
        val terminal = try {
            withTimeout(timeoutMs) {
                runner.buildState.first { state ->
                    // The state is a snapshot whose log only grows, so the lines past what we already
                    // emitted are the new ones.
                    state.log.drop(emitted).forEach(onLog)
                    emitted = state.log.size
                    state.status == RunStatus.Succeeded || state.status == RunStatus.Failed
                }
            }
        } catch (_: TimeoutCancellationException) {
            runner.stopBuild()
            val partial = runner.buildState.value
            return HeadlessBuildResult(
                succeeded = false,
                taskId = taskId,
                moduleName = partial.moduleName,
                elapsedMs = partial.elapsedMs,
                timedOut = true,
                log = partial.log,
                diagnostics = partial.diagnostics,
                outputs = emptyList(),
            )
        }
        return HeadlessBuildResult(
            succeeded = terminal.status == RunStatus.Succeeded,
            taskId = taskId,
            moduleName = terminal.moduleName,
            elapsedMs = terminal.elapsedMs,
            timedOut = false,
            log = terminal.log,
            diagnostics = terminal.diagnostics,
            outputs = if (terminal.status == RunStatus.Succeeded) outputsOf(taskId) else emptyList(),
        )
    }

    /**
     * Where [taskId] puts its deliverable, for the task kinds that have one. The paths are the build
     * system's own (`build/outputs/...`, mirroring AGP's layout), filtered to what actually exists — a
     * task whose output is missing after a successful build reports nothing rather than a dead path.
     */
    private fun outputsOf(taskId: String): List<Path> {
        val parts = taskId.split(':')
        val module = parts.getOrNull(1)?.let { name -> ide.modules().firstOrNull { it.name == name } }
            ?: return emptyList()
        val variant = parts.getOrNull(2)
        val candidates = when (parts[0]) {
            "assemble" -> listOfNotNull(variant?.let { AndroidBuildSystem.signedApkPath(module, it) })
            "bundle" -> listOfNotNull(variant?.let { AndroidBuildSystem.signedAabPath(module, it) })
            "assembleAar" -> listOfNotNull(variant?.let { AndroidBuildSystem.aarPath(module, it) })
            "build" -> jarsOf(module)
            else -> emptyList()
        }
        return candidates.filter { Files.isRegularFile(it) }
    }

    /** The jars a JVM module packages under `build/libs` — the `build:<module>` task's deliverable. */
    private fun jarsOf(module: Module): List<Path> {
        val libs = buildDir(module).resolve("libs")
        if (!Files.isDirectory(libs)) return emptyList()
        return Files.list(libs).use { s ->
            s.filter { it.toString().endsWith(".jar") }.sorted().collect(Collectors.toList())
        }
    }

    override fun close() = ide.close()

    companion object {
        /** Dependency resolution can pull a whole Compose/AndroidX graph over the network on a cold cache. */
        const val DEFAULT_RESOLVE_TIMEOUT_MS: Long = 20 * 60_000

        /** A cold build compiles + dexes the world; a warm one is seconds. */
        const val DEFAULT_BUILD_TIMEOUT_MS: Long = 60 * 60_000

        /** `<root>/.platform/workspace.json` — the file that makes a directory a CodeAssist workspace. */
        fun isWorkspace(root: Path): Boolean =
            Files.isRegularFile(root.resolve(".platform").resolve("workspace.json"))

        /**
         * Open the workspace at [root] for building only: no symbol index, no Kotlin editor warm-ups, just
         * the model + classpath + compilers.
         *
         * [cachesRoot] roots the resolved-dependency cache (`<cachesRoot>/.platform/caches/resolved-deps`).
         * Point it outside the project to share downloads across builds — on CI that directory is the one
         * worth restoring between runs. Null keeps the cache inside the project, as an on-device build does.
         *
         * The workspace SDK comes from an installed Android SDK when one is found (`ANDROID_HOME`,
         * `ANDROID_SDK_ROOT`, `local.properties`, or the per-OS default location), else the running JDK.
         */
        fun open(root: Path, cachesRoot: Path? = null): HeadlessEngine {
            require(Files.isDirectory(root)) { "not a directory: $root" }
            return HeadlessEngine(
                IdeServices.openAt(
                    root = root,
                    sdk = IdeServices.defaultDesktopSdk(),
                    sharedCachesRoot = cachesRoot,
                    buildOnly = true,
                )
            )
        }

        /**
         * Scaffold a new project at [root] from the template [templateId] (`android-app`, `compose-app`,
         * `kotlin-console`, …) and return it open, ready to build. [args] are the template's own parameters
         * plus the reserved `name` and `packageName`.
         *
         * The engine this returns is a full one, not build-only: creation runs the template through the
         * same path the IDE's New Project flow does. Declared dependencies are recorded but not yet
         * downloaded, so the caller resolves them ([resolveDependencies]) before building.
         */
        fun create(
            root: Path,
            templateId: String,
            args: Map<String, String>,
            cachesRoot: Path? = null,
        ): HeadlessEngine = HeadlessEngine(
            IdeServices.createProjectAt(
                root = root,
                templateId = templateId,
                args = args,
                sdk = IdeServices.defaultDesktopSdk(),
                languageLevel = LanguageLevel.JAVA_17,
                sharedCachesRoot = cachesRoot,
            )
        )

        /**
         * Import the foreign-build-system project at [root] into a CodeAssist workspace written there, so
         * the other commands can build it. Returns false, writing nothing, when no importer claims [root].
         *
         * This is what makes a Gradle repository buildable headlessly: run its `generateNativeModel` task
         * (which writes `.platform/gradle-model.json`), import once, and every later `assemble` opens the
         * workspace that produced. A project already carrying a workspace is re-imported in place: modules
         * are added and refreshed, never removed.
         */
        fun importProject(root: Path): Boolean =
            IdeServices.importExternalProjectAt(root, IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17)

        /** The ids [create] accepts, with their display names, as contributed by the template registry. */
        fun templates(): List<Pair<String, String>> =
            ApplicationEnvironment().use { env ->
                ProjectTemplateRegistry(env.platform.extensions).all().map { it.id.value to it.displayName }
            }
    }
}

/** One task offered by a workspace: [id] is what [HeadlessEngine.build] takes, [group] buckets it. */
data class HeadlessTask(val id: String, val label: String, val group: String)

/**
 * The outcome of one headless build. [log] is the full transcript (already streamed line by line if the
 * caller passed an `onLog`), [diagnostics] the structured layer over it, and [outputs] the artifacts the
 * task produced (an APK, an AAB, an AAR, jars) — empty on failure.
 */
class HeadlessBuildResult(
    val succeeded: Boolean,
    val taskId: String,
    val moduleName: String,
    val elapsedMs: Long,
    /** True when the build was stopped for exceeding its time budget rather than finishing. */
    val timedOut: Boolean,
    val log: List<BuildLogLine>,
    val diagnostics: List<BuildDiagnosticUi>,
    val outputs: List<Path>,
)
