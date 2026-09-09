package dev.ide.lang.folding

import dev.ide.lang.dom.TextRange
import dev.ide.vfs.VirtualFile

/**
 * Code-folding SPI — the structural "collapse a region to a placeholder" layer over a tolerant parse.
 *
 * A backend produces a flat list of [FoldRegion]s (offset spans + the text to show when collapsed). The
 * editor anchors them to the live buffer (shifting on edit, like diagnostics/semantic tokens), draws a
 * gutter chevron per region, and renders a collapsed region as `prefix + placeholder + suffix` on one
 * visual line — so a function body reads `fun Test() {...}` and an import block reads `import ...`.
 *
 * Extensible the same way every other editor capability is: a backend opts in by advertising
 * [dev.ide.lang.BackendCapability.CODE_FOLDING] and returning a [FoldingService] from its analyzer (see
 * [dev.ide.lang.SourceAnalyzer.folding]). Adding a language's fold rules is a backend registration, not a
 * host edit; [FoldKind] is an open, string-backed set so a backend can contribute kinds the core doesn't
 * enumerate (the UI treats an unknown kind generically — same chevron, the backend's own placeholder).
 */
interface FoldingService {
    /**
     * The foldable regions in [file]'s current (tolerant) parse. Runs on the shared engine thread, debounced
     * by the host like analysis/semantic-highlighting, and is expected to poll
     * [dev.ide.platform.EngineCancellation] so a higher-priority call (completion) can preempt the pass.
     * Ranges are document offsets; order is unspecified. A region that the host can't anchor (zero-width,
     * single visual line) is dropped by the consumer, so a backend needn't pre-filter for line count.
     */
    suspend fun folds(file: VirtualFile): List<FoldRegion>
}

/**
 * One foldable region: the [range] of text to collapse (document offsets `[start, end)`), the [placeholder]
 * shown in its place when collapsed (e.g. `...`, `{...}`, `/*...*/`), what it IS ([kind]), and whether the
 * editor should collapse it the first time the file opens ([collapsedByDefault] — IntelliJ folds imports and
 * file-header comments by default, code blocks open).
 *
 * The [range] should span exactly the text to hide: for a brace block, from just after `{` to just before
 * `}` (so the braces stay visible around the placeholder → `{...}`); for an import group, the whole run of
 * import statements with `placeholder = "import ..."`. The editor keeps the start line's text up to
 * `range.start` and the end line's text from `range.end`, joining them with [placeholder].
 */
class FoldRegion(
    val range: TextRange,
    val placeholder: String,
    val kind: FoldKind,
    val collapsedByDefault: Boolean = false,
)

/**
 * What a fold is. Open and string-backed (the `NodeKind`/`HighlightKind` convention) so a backend may
 * contribute kinds beyond these built-ins; the UI handles an unknown kind generically.
 */
@JvmInline
value class FoldKind(val id: String) {
    companion object {
        val IMPORTS = FoldKind("imports")             // the import statement group (collapsed by default)
        val CLASS_BODY = FoldKind("classBody")        // a class/object/interface/enum body
        val FUNCTION_BODY = FoldKind("functionBody")  // a function/method/constructor body
        val BLOCK = FoldKind("block")                 // a bare statement block / control-flow body
        val COMMENT = FoldKind("comment")             // a block comment / KDoc / Javadoc
        val STRING = FoldKind("string")               // a multi-line string literal
        val ARGUMENTS = FoldKind("arguments")         // a multi-line argument / parameter list
    }
}
