package dev.ide.lang.kotlin

import dev.ide.lang.dom.TextRange
import dev.ide.lang.folding.FoldKind
import dev.ide.lang.folding.FoldRegion
import dev.ide.lang.folding.FoldingService
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * Kotlin code folding off the parse-only PSI (no resolution needed). Emits a [FoldRegion] for each foldable
 * structure:
 *  - the import group → collapses to `import ...` (collapsed by default, like IntelliJ);
 *  - class/object/interface/enum bodies and function/lambda/control-flow blocks → `{...}` (the braces stay
 *    visible around the placeholder, since the region spans only the text BETWEEN them);
 *  - multi-line block comments / KDoc → `/*...*/`.
 *
 * Only regions that actually span more than one line are emitted (a single-line block can't usefully fold).
 * Polls [EngineCancellation] between nodes so completion can preempt the pass; the host retries.
 *
 * **Incremental**, like the semantic highlighter: a keystroke re-walks only the declarations it can affect
 * ([IncrementalDecls.plan]) and reuses every other declaration's fold regions re-anchored to its shifted
 * offset. Folding a whole file forces the lazy PSI subtree of every declaration to materialize — the dominant
 * cost on a large file (measured ~1.9s per keystroke before this) — so scoping it to the changed declaration
 * is the same win the highlight/diagnostics passes already get, bringing a settled-buffer re-fold to tens of ms.
 */
class KotlinCodeFolder(
    private val parsedFor: (VirtualFile) -> KotlinParsedFile?,
) : FoldingService {

    // One top-level declaration's fold regions, relative to the declaration's start offset (so a later edit that
    // only shifts it re-anchors by adding the new base). [facts] is the change-detection key IncrementalDecls uses.
    private class DeclFolds(val facts: IncrementalDecls.Facts, val rel: List<FoldRegion>)
    // Per-file fold cache: the full previous result (for the identical-text fast path — a preempt-retry or the
    // daemon's re-run once the index finishes reuses it without any walk) plus the per-declaration entries and
    // the import/text surface the next edit's [IncrementalDecls.plan] diffs against.
    private class Cached(
        val imports: IncrementalDecls.Imports,
        val fileText: String,
        val decls: List<DeclFolds>,
        val regions: List<FoldRegion>,
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    override suspend fun folds(file: VirtualFile): List<FoldRegion> {
        val parsed = parsedFor(file) ?: return emptyList()
        val ktFile = parsed.ktFile
        val text = ktFile.text
        val prev = cache[file.path]
        // Folding is a pure function of the text, so an identical buffer reuses the whole result — no walk.
        prev?.let { if (it.fileText.contentEquals(text)) return it.regions }

        val out = ArrayList<FoldRegion>(32)
        // The import group: from the first import directive to the last, shown as `import ...`. Cheap + top-level
        // (not owned by any declaration), so recomputed fresh each pass.
        ktFile.importList?.imports?.takeIf { it.isNotEmpty() }?.let { imports ->
            addRegion(out, text, imports.first().textRange.startOffset, imports.last().textRange.endOffset, "import ...", FoldKind.IMPORTS, collapsedByDefault = true)
        }
        // Top-level non-declaration children (file/license block comments, etc.) — cheap, recomputed fresh; the
        // declaration subtrees (the expensive part) go through the incremental plan below.
        for (child in ktFile.children) if (child !is KtDeclaration) walkFolds(child, text, out)

        val topDecls = ktFile.declarations
        val curImports = IncrementalDecls.importsOf(ktFile)
        // Recompute only the declarations this edit can affect; reuse the rest re-anchored. Folding depends only
        // on a declaration's OWN text (pure syntax), so the plan's dependency edges just over-approximate here —
        // always sound. Same plan the highlight/diagnostics passes use (see IncrementalDecls).
        val plan = IncrementalDecls.plan(prev?.decls?.map { it.facts }, prev?.imports, prev?.fileText, topDecls, curImports, text)
        val recompute: Set<Int>? = (plan as? IncrementalDecls.Plan.Partial)?.recompute
        val newEntries = ArrayList<DeclFolds>(topDecls.size)
        for ((i, d) in topDecls.withIndex()) {
            val base = d.textRange.startOffset
            if (recompute != null && i !in recompute) {
                val cached = prev!!.decls[i] // unaffected → reuse this declaration's regions, re-anchored
                cached.rel.forEach { out += shift(it, base) }
                newEntries += cached
            } else {
                val abs = ArrayList<FoldRegion>()
                walkFolds(d, text, abs)
                out += abs
                newEntries += DeclFolds(IncrementalDecls.factsOf(d), abs.map { shift(it, -base) })
            }
        }
        cache[file.path] = Cached(curImports, text, newEntries, out)
        return out
    }

    /** Shift a fold region's range by [delta] (relative⇄absolute re-anchoring; [FoldRegion] has no copy()). */
    private fun shift(r: FoldRegion, delta: Int): FoldRegion =
        FoldRegion(TextRange(r.range.start + delta, r.range.end + delta), r.placeholder, r.kind, r.collapsedByDefault)

    /** Emit fold regions for [root]'s subtree (brace blocks, class bodies, block comments/KDoc).
     *
     *  Hard-bounded by [MAX_FOLD_NODES]: a materialized PSI DFS visits each node once, so a real file is at
     *  most thousands of nodes — a count far beyond that means a pathological traversal (a fold pass was
     *  observed looping for minutes on a device, freeing ~100MB/s of transient garbage). Rather than hang the
     *  editor's first pass, bail (folds are best-effort) and log WHERE it tripped so the offending node type +
     *  range is on record. */
    private fun walkFolds(root: PsiElement, text: CharSequence, out: MutableList<FoldRegion>) {
        var seen = 0
        var tripped = false
        fun walk(psi: PsiElement) {
            if (seen++ % 64 == 0) EngineCancellation.checkCanceled()
            if (seen > MAX_FOLD_NODES) {
                if (!tripped) {
                    tripped = true
                    foldLog.warn(
                        "fold walk exceeded $MAX_FOLD_NODES nodes; bailing at " +
                            "${psi::class.java.simpleName} range=${psi.textRange} — folds may be incomplete"
                    )
                }
                return
            }
            when (psi) {
                is KtClassBody -> braceBlock(out, text, psi.lBrace, psi.rBrace, FoldKind.CLASS_BODY)
                is KtBlockExpression -> braceBlock(out, text, psi.lBrace, psi.rBrace, FoldKind.FUNCTION_BODY)
                is KDoc -> addRegion(out, text, psi.textRange.startOffset, psi.textRange.endOffset, "/**...*/", FoldKind.COMMENT)
                is PsiComment -> if (psi.tokenType == org.jetbrains.kotlin.lexer.KtTokens.BLOCK_COMMENT)
                    addRegion(out, text, psi.textRange.startOffset, psi.textRange.endOffset, "/*...*/", FoldKind.COMMENT)
                else -> {}
            }
            var c = psi.firstChild
            while (c != null && !tripped) { walk(c); c = c.nextSibling }
        }
        walk(root)
    }

    /** A `{ … }` block: fold the text strictly BETWEEN the braces so `{` and `}` stay visible → `{...}`. */
    private fun braceBlock(out: MutableList<FoldRegion>, text: CharSequence, lBrace: PsiElement?, rBrace: PsiElement?, kind: FoldKind) {
        if (lBrace == null || rBrace == null) return
        addRegion(out, text, lBrace.textRange.endOffset, rBrace.textRange.startOffset, "...", kind)
    }

    /** Emit a region only when it spans more than one line (a same-line block isn't worth a fold). */
    private fun addRegion(out: MutableList<FoldRegion>, text: CharSequence, start: Int, end: Int, placeholder: String, kind: FoldKind, collapsedByDefault: Boolean = false) {
        if (end <= start || end > text.length) return
        var multiline = false
        for (i in start until end) if (text[i] == '\n') { multiline = true; break }
        if (!multiline) return
        out += FoldRegion(TextRange(start, end), placeholder, kind, collapsedByDefault)
    }

    private companion object {
        private val foldLog = dev.ide.platform.log.Log.logger("kotlin.fold")

        /** Ceiling on PSI nodes visited per declaration's fold walk. A materialized DFS visits each node once,
         *  so even a large file is a few thousand — 500k is a ~100x safety margin over any real Kotlin file and
         *  only trips on a pathological (e.g. cyclic) traversal. */
        private const val MAX_FOLD_NODES = 500_000
    }
}
