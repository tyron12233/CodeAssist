package dev.ide.core.build

import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiSeverity

/**
 * Categorizes a FAILED build into a coarse, privacy-safe bucket for analytics (see `docs/analytics.md`).
 * The fleet shows roughly half of all builds "failing", but that bit is meaningless on its own: a compile
 * error in the user's own code is the expected inner loop, whereas the build machinery throwing without a
 * diagnostic is our bug. This turns the single `ok=false` flag into an actionable class.
 *
 * Reads ONLY structured diagnostic severities/kinds, log LEVELS, and a couple of JVM exception-*type* tokens
 * (`OutOfMemoryError`) — never messages, file names, or user content. The result shipped is just the bucket
 * name below.
 */
internal object BuildFailureKind {
    /** Failed but produced no structured error diagnostic: a thrown task, a missing tool, an environment
     *  problem. The bucket worth watching — most likely a defect on our side, not the user's code. */
    const val NO_DIAGNOSTIC = "no_diagnostic"

    // The last is what the dex/shrink tools' output is rewritten to before it reaches the console: without it a
    // build that R8 or D8 failed for lack of heap was counted as a generic tool failure.
    private val OOM_TOKENS = listOf("OutOfMemoryError", "OutOfMemory", "ran out of memory")

    fun classify(diagnostics: List<BuildDiagnosticUi>, log: List<BuildLogLine>): String {
        val errors = diagnostics.filter { it.severity == UiSeverity.Error }
        // OOM first: it can masquerade as any other failure and is the memory-pressure signal we most want.
        val oom = errors.any { hitsOom(it.message) || hitsOom(it.detail) } ||
            log.any { it.level == UiLogLevel.Error && hitsOom(it.message) }
        if (oom) return "oom"
        if (errors.isEmpty()) return NO_DIAGNOSTIC
        // Bucket by the dominant error-producing stage. Compiler/resource errors are almost always the user's
        // own code/XML (the healthy inner loop); dex/packaging/signing point at our pipeline or device limits.
        val kinds = errors.map { it.kind.lowercase() }.toSet()
        return when {
            "compiler" in kinds -> "compile"
            "resource" in kinds -> "resource"
            kinds.any { it == "dex" || it == "packaging" || it == "signing" || it == "sign" } -> "tool"
            else -> "other"
        }
    }

    private fun hitsOom(s: String?): Boolean = s != null && OOM_TOKENS.any { s.contains(it) }
}

/**
 * What a build's console says about its R8 run, as analytics properties: where R8 ran, with how large a heap,
 * and whether it ran out of memory and shrank the project's own code only. Without these an out-of-memory
 * report cannot be tied to the memory R8 was given, and a build the fallback saved looks like any other success.
 * Reads only the shrinker's own fixed notes, never user content. Empty for a build that did not minify.
 */
internal object R8BuildFacts {
    private val FORKED = Regex("""R8: forked VM, (\d+)MB heap""")
    private const val IN_PROCESS = "R8: in-process"

    fun props(log: List<BuildLogLine>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in log) {
            val text = line.message
            FORKED.find(text)?.let { out["r8_mode"] = "forked"; out["r8_heap_mb"] = it.groupValues[1] }
            if (IN_PROCESS in text) out["r8_mode"] = "in-process"
            if (dev.ide.android.support.tools.ToolMemory.isShrinkFallback(text)) out["r8_fallback"] = "true"
        }
        return out
    }
}
