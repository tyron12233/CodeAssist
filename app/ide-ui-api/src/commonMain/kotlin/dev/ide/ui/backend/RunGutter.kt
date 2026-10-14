package dev.ide.ui.backend

/**
 * The editor's run marks beside a `main`: what their action ids carry, and the Run row a mark's run becomes.
 *
 * A mark's action is a Run task id, prefixed ([ACTION_PREFIX]) so the editor starts it as a run rather than
 * dispatching it as a plugin action. Running one also makes it the Run button's task, as IntelliJ's temporary
 * configuration does, so the next Run (button or shortcut) starts the same `main` again.
 */
object RunGutter {
    /** Prefixes a gutter mark's action id that is a `run:` task id: tapping it starts that run. */
    const val ACTION_PREFIX = "ca.run:"

    /** The Run task that launches [mainClass] (a binary class name) of console module [module]. */
    fun mainTaskId(module: String, mainClass: String): String = "run:$module:$mainClass"

    /** The class [mainTaskId] names, or null for any other task (a module's default `run:<module>` among them). */
    fun mainClassOf(taskId: String): String? =
        taskId.takeIf { it.startsWith("run:") }?.removePrefix("run:")?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }

    /**
     * The Run row for [taskId] started from a gutter mark (a `main` or a test), when the Run picker does not list
     * it: labelled after what it runs, the way IntelliJ names a temporary configuration (`MainKt`, `FooTest`,
     * `FooTest.bar`).
     */
    fun option(taskId: String): RunTaskOption {
        mainClassOf(taskId)?.let { return RunTaskOption(taskId, "Run ${simpleName(it)}", "run") }
        val (module, targets) = TestOutput.parseTaskId(taskId) ?: return RunTaskOption(taskId, taskId, "run")
        val label = when {
            targets.isEmpty() -> "Test $module"
            targets.size > 1 -> "${targets.size} tests in $module"
            else -> targets.single().let { t ->
                val cls = simpleName(t.substringBefore('#'))
                if ('#' in t) "$cls.${t.substringAfter('#')}" else cls
            }
        }
        return RunTaskOption(taskId, label, "test")
    }

    private fun simpleName(binary: String) = binary.substringAfterLast('.').substringAfterLast('$')
}
