package dev.ide.build

import dev.ide.model.Project

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

/** What a [Plugin] sees: the project/request being built and the [tasks] container to contribute to. */
interface BuildConfiguration {
    val project: Project
    val request: BuildRequest
    val tasks: TaskContainer
}
