package dev.ide.build

/**
 * Structured, kind-tagged build diagnostics that a [Task] streams *while it runs* — not a text log
 * accumulated and parsed at the end.
 *
 * The plain `ctx.logger()` channel ([TaskContext.logger]) stays the raw transcript (a program's stdout,
 * step banners, untyped tool chatter); diagnostics are the *structured* layer over it: each carries a
 * [severity], an extensible [kind], where it came from ([source]), an optional source [location], a tool
 * code, and free-form [detail]. A UI can therefore group, count, color, and make them click-to-open
 * without re-scraping text.
 *
 * Tasks push them through [TaskContext.diagnostics] the moment they're known (e.g. a compiler emits one
 * per problem as it parses each tool line), so the console fills in live instead of waiting for the task
 * to finish.
 */
data class BuildDiagnostic(
    val severity: BuildSeverity,
    val message: String,
    /** Open classification (compiler / resource / dex / dependency / lint / …); drives icon + grouping. */
    val kind: DiagnosticKind = DiagnosticKind.GENERIC,
    /** Producer label: "javac", "kotlinc", "d8", "aapt2", a tool name — shown as the diagnostic's origin. */
    val source: String = "",
    /** Where in the project it points, if any. Absent for tool-global messages (e.g. a dexer summary). */
    val location: DiagnosticLocation? = null,
    /** Tool/compiler-specific code (an ecj problem id, an aapt2 error tag) — for filtering/dedup. */
    val code: String? = null,
    /** Extra multi-line context: a source snippet, a hint, or the raw tool line the structured fields came from. */
    val detail: String? = null,
    /** The task that emitted it. Left null by the producer; the engine fills it in (see [TaskExecutor]). */
    val task: TaskName? = null,
)

enum class BuildSeverity { ERROR, WARNING, INFO }

/**
 * An open, extensible classification of a [BuildDiagnostic] — string-backed per the project's convention
 * for extensible sets (a plugin can mint its own without touching this file). The built-ins cover the
 * native pipeline; the UI falls back to a generic icon for unknown ids.
 */
@JvmInline
value class DiagnosticKind(val id: String) {
    companion object {
        val COMPILER = DiagnosticKind("compiler")     // javac / ecj / kotlinc problems
        val RESOURCE = DiagnosticKind("resource")     // aapt2 resource compile/link
        val DEX = DiagnosticKind("dex")               // d8 / r8 dexing & desugaring
        val DEPENDENCY = DiagnosticKind("dependency") // resolution / classpath
        val LINT = DiagnosticKind("lint")             // static analysis run as part of a build
        val PACKAGING = DiagnosticKind("packaging")   // apk packaging / signing
        val GENERIC = DiagnosticKind("generic")
    }
}

/**
 * A source location for a diagnostic. [path] is an absolute file path; [line]/[column] are 1-based, and
 * any field is -1 when unknown (a file-level message has only [path], a line-level message omits the
 * range end). Kept path-based (not a [dev.ide.vfs.VirtualFile]) so tool adapters that only know a string
 * path can produce one without a VFS handle.
 */
data class DiagnosticLocation(
    val path: String,
    val line: Int = -1,
    val column: Int = -1,
    val endLine: Int = -1,
    val endColumn: Int = -1,
)

/**
 * The streaming target a [Task] reports diagnostics to. The engine supplies one per run (via
 * [TaskContext.diagnostics]) and tags each reported diagnostic with the running task; the host forwards
 * them to the UI as they arrive.
 */
fun interface DiagnosticSink {
    fun report(diagnostic: BuildDiagnostic)

    companion object {
        /** Discards everything — the default for contexts that don't care about structured diagnostics. */
        val NOOP = DiagnosticSink { }
    }
}
