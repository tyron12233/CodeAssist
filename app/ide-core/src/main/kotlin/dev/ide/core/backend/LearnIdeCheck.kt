package dev.ide.core.backend

import dev.ide.ui.backend.UiExerciseResult

/**
 * One thing a hands-on CodeAssist lesson checks actually happened in the learner's workspace: a file now has
 * the import the lightbulb adds, a library is declared, the program ran and printed something, a commit
 * exists. A step passes when every condition holds, and the result lists each one with a tick or a cross,
 * so the learner sees what is left.
 *
 * Conditions are read from the workspace when Check is pressed (pull, not events): what matters is the state
 * the learner reached, however they reached it, and it survives the app restarting mid-lesson.
 * "Since the step started" conditions compare against the moment the step was first opened, so a commit or
 * an APK left over from an earlier attempt does not pass a new one.
 */
internal sealed interface IdeCondition {
    /** How the learner is told about this condition, ticked or not ("LocalDate is imported"). */
    val label: String

    /** The workspace file at [path] (relative to its root) contains [text] exactly. */
    data class FileContains(val path: String, val text: String, override val label: String) : IdeCondition

    /** The workspace file at [path] no longer contains [text] (a rename left no old name behind). */
    data class FileLacks(val path: String, val text: String, override val label: String) : IdeCondition

    /** The workspace's app module compiles. */
    data class Compiles(override val label: String) : IdeCondition

    /** A run started during this step printed [text] (the Run button, with input typed in the console). */
    data class RunOutput(val text: String, override val label: String) : IdeCondition

    /** A module declares a library whose coordinate starts with [coordinate] (`group:artifact`). */
    data class Dependency(val coordinate: String, override val label: String) : IdeCondition

    /** At least one signing keystore exists in the app's keystore manager. */
    data class Keystore(override val label: String) : IdeCondition

    /** An APK was built in the workspace since the step started. */
    data class ApkBuilt(override val label: String) : IdeCondition

    /** A Git commit was made in the workspace since the step started. */
    data class GitCommit(override val label: String) : IdeCondition
}

/**
 * What [checkIde] reads from the workspace. An interface so the conditions are testable without an engine,
 * and so the backend decides how each fact is found (an open editor buffer wins over the file on disk).
 */
internal interface WorkspaceProbe {
    /** The text of the workspace file at [path], or null when it does not exist. */
    fun read(path: String): String?

    /** Compile the app module: null when it compiled, else the compiler's messages. */
    suspend fun compileErrors(): List<String>?

    /** The output of a run started since the step began, or null when there has been none. */
    fun runOutputSinceStep(): String?

    /** Every declared library coordinate (`group:artifact:version`) across the workspace's modules. */
    fun dependencies(): List<String>

    fun keystoreCount(): Int

    /** Newest modification time of an `.apk` under the workspace's build folders, or 0 when there is none. */
    fun newestApkMillis(): Long

    /** The workspace's `.git/logs/HEAD` text, or null when it is not a repository. */
    fun gitReflog(): String?

    /** When the step was first opened, epoch millis. */
    val stepStartedMillis: Long
}

/** Grade a hands-on step: every condition must hold. Compilation runs once and only when asked for. */
internal suspend fun checkIde(conditions: List<IdeCondition>, probe: WorkspaceProbe): UiExerciseResult {
    val lines = ArrayList<String>()
    val unmet = ArrayList<String>()
    val diagnostics = ArrayList<String>()
    for (c in conditions) {
        val ok = when (c) {
            is IdeCondition.FileContains -> probe.read(c.path)?.contains(c.text) == true
            is IdeCondition.FileLacks -> probe.read(c.path)?.contains(c.text) == false
            is IdeCondition.Compiles -> {
                val errors = probe.compileErrors()
                if (errors != null) diagnostics += errors
                errors == null
            }
            is IdeCondition.RunOutput -> probe.runOutputSinceStep()?.contains(c.text) == true
            is IdeCondition.Dependency -> probe.dependencies().any { it.startsWith(c.coordinate) }
            is IdeCondition.Keystore -> probe.keystoreCount() > 0
            is IdeCondition.ApkBuilt -> probe.newestApkMillis() >= probe.stepStartedMillis
            is IdeCondition.GitCommit -> commitsSince(probe.gitReflog(), probe.stepStartedMillis) > 0
        }
        lines += (if (ok) "✓ " else "✗ ") + c.label
        if (!ok) unmet += c.label
    }
    val passed = unmet.isEmpty()
    return UiExerciseResult(
        passed = passed,
        compiled = true,
        output = lines.joinToString("\n"),
        message = if (passed) "Done, nicely done!" else "Not yet. Still to do: " + unmet.joinToString("; ") + ".",
        diagnostics = diagnostics,
    )
}

/**
 * Commits in a reflog made at or after [sinceMillis]. A reflog line is
 * `<old> <new> <name> <email> <epochSeconds> <tz>\t<message>`; commits are the messages starting `commit`
 * (`commit:`, `commit (initial):`, `commit (amend):`).
 */
internal fun commitsSince(reflog: String?, sinceMillis: Long): Int {
    if (reflog.isNullOrBlank()) return 0
    return reflog.lineSequence().count { line ->
        val tab = line.indexOf('\t')
        if (tab < 0) return@count false
        val message = line.substring(tab + 1)
        if (!message.startsWith("commit")) return@count false
        val head = line.substring(0, tab).trim().split(' ')
        val seconds = head.getOrNull(head.size - 2)?.toLongOrNull() ?: return@count false
        seconds * 1000 >= sinceMillis - CLOCK_SLACK_MS
    }
}

/** Git stamps whole seconds; allow the second the step opened in. */
private const val CLOCK_SLACK_MS = 1_000L
