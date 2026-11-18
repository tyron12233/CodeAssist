package dev.codeassist.csharp.build.tasks

import dev.ide.plugin.editor.GutterActions

/**
 * IDs for this plugin's Run rows.
 *
 * `csRun:<module>` runs the module's entry point: the class configured in Module Settings > Run,
 * or the first `Main` if none is configured.
 *
 * `csRun:<module>:<class>` runs the specified class's `Main` using `-main:<class>`.
 *
 * Keeps the host's `<kind>:<module>[:...]` format, which the Run button uses to determine whether
 * a remembered task still belongs to the project. The module therefore follows the kind directly.
 */
internal object CsRunIds {

    const val PREFIX = "csRun:"

    /**
     * Prefix for an editor gutter action that starts a Run task. The constant's text is compiled into this
     * plugin, so it needs nothing from a host older than the SPI that named it.
     */
    const val GUTTER_ACTION_PREFIX = GutterActions.RUN_TASK_PREFIX

    /** Prefix for a module's Restore row in the Run picker: `csRestore:<module>`. */
    const val RESTORE_PREFIX = "csRestore:"

    fun restoreId(module: String): String = RESTORE_PREFIX + module

    /** Returns the module named by a Restore row ID, or null if the ID does not match. */
    fun restoreModule(id: String): String? =
        if (id.startsWith(RESTORE_PREFIX)) {
            id.removePrefix(RESTORE_PREFIX).ifEmpty { null }
        } else {
            null
        }

    class Parsed(val module: String, val mainClass: String?)

    fun taskId(module: String, mainClass: String? = null): String =
        PREFIX + module + (mainClass?.let { ":$it" } ?: "")

    /** Parses a Run task ID, or returns null if it does not belong to this plugin. */
    fun parse(id: String): Parsed? {
        if (!id.startsWith(PREFIX)) return null

        val rest = id.removePrefix(PREFIX)
        val module = rest.substringBefore(':')
        if (module.isEmpty()) return null

        return Parsed(module, rest.substringAfter(':', "").ifEmpty { null })
    }

    /** Returns the simple name: `Namespace.Outer.Program` becomes `Program`. */
    fun simpleName(mainClass: String): String = mainClass.substringAfterLast('.')
}