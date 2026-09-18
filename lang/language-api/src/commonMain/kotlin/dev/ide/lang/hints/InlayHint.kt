package dev.ide.lang.hints

import dev.ide.lang.dom.TextRange
import dev.ide.lang.resolve.Symbol
import dev.ide.vfs.VirtualFile

/**
 * Inlay hints — the small inline annotations the editor renders *between* characters without changing the
 * document: parameter names at call sites (`foo(/*count:*/ 3)`), inferred types on `var`/lambda params,
 * chained-call return types. They are derived from data [dev.ide.lang.SourceAnalyzer] already exposes
 * (`resolve`, `expectedTypeAt`, `resolveType`); this is the surface that turns it into render-ready hints.
 *
 * Like completion, hints stay backend-neutral: a hint is text [parts] (optionally each backed by a
 * [Symbol] so a type hint is click-to-navigate) anchored at a document [offset]. The editor positions and
 * styles them; it needs no language knowledge.
 */
data class InlayHint(
    /** Document offset the hint is anchored at (rendered immediately before the char at this offset). */
    val offset: Int,
    val parts: List<InlayHintPart>,
    val kind: InlayHintKind,
    val tooltip: String? = null,
    /** Render a thin space before/after the hint (e.g. type hints pad left, parameter hints pad right). */
    val paddingLeft: Boolean = false,
    val paddingRight: Boolean = false,
)

/** One run of a hint's label. [symbol] makes the run navigable (go-to-def on a rendered type name). */
data class InlayHintPart(val text: String, val symbol: Symbol? = null)

enum class InlayHintKind {
    PARAMETER,  // argument name at a call site
    TYPE,       // inferred type of a `var` / lambda parameter / field
    CHAINING,   // intermediate type in a fluent call chain
    OTHER,
}

/**
 * Produces inlay hints for the visible [range] of a [file], so a backend can compute lazily per viewport
 * rather than for the whole file. Returned offsets must fall within (or be clamped to) the document.
 */
interface InlayHintService {
    suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint>
}
