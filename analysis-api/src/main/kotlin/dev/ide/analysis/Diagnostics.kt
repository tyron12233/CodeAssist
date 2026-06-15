package dev.ide.analysis

import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile

/**
 * analysis-api — an extensible framework for running analyzers over the neutral DOM that produce
 * diagnostics, with the compiler plugged in as just another diagnostic source.
 *
 * A compiler error and an analyzer finding are modeled as the same kind of thing: a problem at a
 * source range, with a severity and (optionally) fixes. There is exactly one [Diagnostic] model and
 * one pipeline; the editor underlines and the Problems view consume one merged, deduplicated,
 * suppression-filtered stream regardless of who produced each entry.
 */

/** Identity of a registered analyzer. */
@JvmInline
value class AnalyzerId(val value: String)

/**
 * The enriched diagnostic used throughout the pipeline. It carries everything the raw
 * `dom.Diagnostic` does (range/severity/message/code over the error-tolerant DOM) plus the fields
 * analysis needs: which source produced it, attached [fixes], rendering [tags], and secondary
 * [relatedRanges]. Compiler output is adapted into this exact type (see [DiagnosticProvider]) so from
 * the merge step onward, compiler errors and analyzer findings are indistinguishable.
 *
 * A diagnostic is always published *for a particular file* (see [AnalysisService.diagnostics]); its
 * [range] is an offset span within that file. Secondary locations in other files go in [relatedRanges].
 */
data class Diagnostic(
    val range: TextRange,
    val severity: Severity,
    val message: String,
    val source: DiagnosticSource,
    /**
     * Stable, machine-readable id (e.g. `"UNRESOLVED_REFERENCE"`, `"unused.import"`). This is the
     * join key that lets a [QuickFixProvider] attach actions to diagnostics it did not author,
     * including the compiler's. See [Codes] for the conventional compiler codes.
     */
    val code: String? = null,
    val fixes: List<QuickFix> = emptyList(),
    /** Drives rendering: [DiagnosticTag.UNUSED] greys out, [DiagnosticTag.DEPRECATED] strikes through. */
    val tags: Set<DiagnosticTag> = emptySet(),
    /** "declared here" / secondary locations, possibly in other files. */
    val relatedRanges: List<RelatedRange> = emptyList(),
)

/** Where a [Diagnostic] came from — a specific analyzer, or the compiler backend. */
sealed interface DiagnosticSource {
    data class Analyzer(val id: AnalyzerId) : DiagnosticSource
    object Compiler : DiagnosticSource
}

/** Cross-cutting markers that change how the editor renders a diagnostic (not its severity). */
enum class DiagnosticTag { UNUSED, DEPRECATED }

/** A secondary range tied to a diagnostic — possibly in another file (e.g. the conflicting declaration). */
data class RelatedRange(val file: VirtualFile, val range: TextRange, val message: String)

/**
 * Conventional, backend-neutral diagnostic [Diagnostic.code]s. These are plain strings used as join
 * keys: the compiler emits them, and code-keyed [QuickFixProvider]s match on them.
 * Backends should map their native error ids onto these where the meaning matches, so a fix written
 * for `UNRESOLVED_REFERENCE` works whether JDT, javac, or the Kotlin compiler produced the error.
 */
object Codes {
    const val UNRESOLVED_REFERENCE = "UNRESOLVED_REFERENCE"
    const val MISSING_SEMICOLON = "MISSING_SEMICOLON"
    const val UNUSED_IMPORT = "unused.import"
    const val UNUSED_LOCAL = "unused.local"
    const val MISSING_OVERRIDE = "MISSING_OVERRIDE"
    const val CREATE_FROM_USAGE = "CREATE_FROM_USAGE"
}
