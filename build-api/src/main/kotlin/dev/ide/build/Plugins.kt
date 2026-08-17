package dev.ide.build

import dev.ide.model.BuildSystemId
import dev.ide.model.Module
import dev.ide.model.Project
import dev.ide.platform.ExtensionPoint
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The configuration phase, Gradle-style. Plugins register tasks **lazily** (the factory runs only when the
 * graph is realized) and wire relationships **by name** — including to tasks another plugin will register
 * later. [build] materializes everything into an executable [TaskGraph]: it runs the factories, applies the
 * deferred configuration actions, resolves the name edges, and detects cycles.
 *
 * This mirrors Gradle's `tasks.register(name) { … }` / `tasks.named(name).configure { dependsOn(…) }` so a
 * plugin (e.g. Android) can depend on another plugin's task (e.g. the Java plugin's `:lib:jar`) without the
 * two having to know each other's order of application.
 */
interface TaskContainer {
    /** Register a task, created lazily by [create] at realize time; returns a handle to configure it. */
    fun register(name: TaskName, create: () -> Task): TaskProvider

    /** A lazy handle to [name] whether or not it is registered yet — configuration is deferred to realize. */
    fun named(name: TaskName): TaskProvider

    /** Apply [action] to every task — those already registered and those registered later. */
    fun configureEach(action: TaskSpec.() -> Unit)

    /** Realize: run factories, apply all configuration, resolve name edges → an executable [TaskGraph]. */
    fun build(): TaskGraph
}

/** A lazy reference to a (possibly not-yet-registered) task; [configure] is deferred until realize. */
interface TaskProvider {
    val name: TaskName
    fun configure(action: TaskSpec.() -> Unit): TaskProvider
}

/** The configurable view of a task during the configuration phase: declare its relationships. Each accepts
 *  a mix of [TaskProvider], [TaskName] or [String]; a name that is never registered is simply ignored. */
interface TaskSpec {
    val name: TaskName
    /** Hard dependencies: must finish successfully before this task; their failure blocks it. */
    fun dependsOn(vararg tasks: Any)
    /** Ordering only — sequence after these when present, without depending on or blocking on them. */
    fun mustRunAfter(vararg tasks: Any)
    /** Ordering only — sequence before these when present. */
    fun mustRunBefore(vararg tasks: Any)
}

/** A unit of build logic that contributes tasks to a build (Gradle's `Plugin<Project>`). */
interface Plugin {
    fun apply(config: BuildConfiguration)
}

/**
 * What a [Plugin] sees: the project/request being built, the [tasks] container to contribute to, which build
 * system's graph is being assembled, and the paths/classpaths a contributed task needs ([env]).
 */
interface BuildConfiguration {
    val project: Project
    val request: BuildRequest
    val tasks: TaskContainer

    /** The build system realizing this graph, so a plugin can key off `native` vs `android` conventions. */
    val buildSystemId: BuildSystemId get() = BuildSystemId.NATIVE

    /** Host paths and per-module platform classpath. Derived from [project] when the host supplies none. */
    val env: BuildEnv get() = BuildEnv.of(project)
}

/**
 * The build-time environment a contributed task resolves paths and classpaths through, so a plugin never has
 * to rediscover the host's layout or the platform SDK. Supplied by the host in [BuildContext]; the
 * [BuildEnv.of] fallback derives what it can from the project alone.
 */
interface BuildEnv {
    /** The open workspace root (the directory holding the project's model and `.platform`). */
    val workspaceRoot: Path

    /** Cross-project cache root (shared dex/dependency caches), or null when caches are per-project. */
    val sharedCachesRoot: Path? get() = null

    /** The module's platform (boot) classpath: its SDK jars. Empty means the host JVM's own platform. */
    fun bootClasspath(module: Module): List<Path> = emptyList()

    /** The module's build directory (`<moduleDir>/build`), where generated and intermediate output belongs. */
    fun buildDir(module: Module): Path =
        Paths.get(module.outputDir.path).parent ?: Paths.get(module.outputDir.path)

    /** A private output directory for [id] under the module's build dir, e.g. `build/generated/<id>`. */
    fun generatedDir(module: Module, id: String): Path = buildDir(module).resolve("generated").resolve(id)

    companion object {
        /** A minimal environment derived from [project]: its root as the workspace, no SDK, no shared cache. */
        fun of(project: Project): BuildEnv = object : BuildEnv {
            override val workspaceRoot: Path = Paths.get(project.rootDir.path)
        }
    }
}

/**
 * A [Plugin] a *plugin module* contributes, so build logic can be added without owning a whole
 * [BuildSystem]. The host applies every contributed plugin that [appliesTo] a graph after the build
 * system's own plugins and before the container is realized, so a contributed task can be wired by name to
 * the tasks the build system just registered (`:app:compileJava`, `:app:classes`, `:app:assemble`).
 *
 * ```
 * class BuildInfoPlugin : BuildPlugin {
 *     override val id = "build-info"
 *     override fun appliesTo(config: BuildConfiguration) = config.request.goal != BuildGoal.CLEAN
 *     override fun apply(config: BuildConfiguration) {
 *         for (m in config.project.modules) {
 *             val gen = TaskName(":${m.name}:generateBuildInfo")
 *             config.tasks.register(gen) { WriteBuildInfoTask(gen, config.env.generatedDir(m, "build-info")) }
 *             config.tasks.named(TaskName(":${m.name}:compileJava")).configure { dependsOn(gen) }
 *         }
 *     }
 * }
 * ```
 *
 * Configuring a task that no build system registered is silently ignored, so one plugin can target several
 * pipelines without probing which one is running.
 */
interface BuildPlugin : Plugin {
    /** Stable id, used in logs and to let a host drop a duplicate registration. */
    val id: String

    /** True when this plugin should contribute to the graph described by [config]. */
    fun appliesTo(config: BuildConfiguration): Boolean = true
}

/** Plugins contribute build logic here; every applicable one is applied to each realized graph. */
val BUILD_PLUGIN_EP = ExtensionPoint<BuildPlugin>("platform.buildPlugin")

/**
 * The lifecycle task names a build system registers per module, and the anchors a [BuildPlugin] wires to.
 * A pipeline registers the ones that make sense for its language (Android adds its own variant-suffixed
 * steps), and every one of them is optional: configuring an absent task is a no-op.
 */
object Lifecycle {
    /** Sources compiled: `:<module>:compileJava` (and `:compileKotlin` ahead of it for Kotlin modules). */
    fun compileJava(moduleName: String) = TaskName(":$moduleName:compileJava")

    fun compileKotlin(moduleName: String) = TaskName(":$moduleName:compileKotlin")

    /** Generated sources emitted, ahead of every compile task. */
    fun generateSources(moduleName: String) = TaskName(":$moduleName:generateSources")

    /** Resources copied into the module's output. */
    fun processResources(moduleName: String) = TaskName(":$moduleName:processResources")

    /** Everything compiled and resources in place: the aggregate to hook before packaging. */
    fun classes(moduleName: String) = TaskName(":$moduleName:classes")

    /** The module's jar. */
    fun jar(moduleName: String) = TaskName(":$moduleName:jar")

    /**
     * The module's build products, complete: the last aggregate in the graph. A plugin's own final task
     * belongs on this one (`tasks.named(assemble(m)).configure { dependsOn(mine) }`). Android pipelines
     * suffix it with the variant being built (`:app:assembleDebug`), so pass [variant] there.
     */
    fun assemble(moduleName: String, variant: String? = null): TaskName =
        TaskName(":$moduleName:assemble" + (variant?.replaceFirstChar { it.uppercase() } ?: ""))
}
