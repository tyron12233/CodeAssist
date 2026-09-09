package dev.ide.core

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

    private val OOM_TOKENS = listOf("OutOfMemoryError", "OutOfMemory")

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
