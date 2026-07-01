package dev.ide.lang.kotlin

import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Shared per-top-level-declaration incremental-reuse machinery for the editor passes that are "Σ over the
 * file's top-level declarations" (semantic diagnostics via [IncrementalSemanticAnalysis], semantic
 * highlighting via [KotlinSemanticHighlighter]): cache each declaration's results and, on the next edit,
 * recompute only the declaration(s) that changed, reusing the rest re-anchored to their shifted offsets.
 *
 * Change detection is by TEXT (per declaration). NOTE: PSI node identity is NOT usable here — IntelliJ's
 * incremental reparse ([dev.ide.lang.kotlin.parse.KotlinPsiMutation]) keeps the SAME composite PsiElement
 * instance for a top-level declaration even when its children change (it surgically swaps only the edited
 * subtree), so `decl === cachedNode` is true for a CHANGED declaration too and can't tell changed from
 * unchanged. A text compare is the sound signal; it's also far cheaper than the per-reference resolution the
 * reuse avoids, so it's not the bottleneck.
 *
 * Soundness (why reusing the OTHER declarations is safe): a declaration's results depend on its own subtree
 * plus the symbols visible to it — imports, sibling top-level declarations' SIGNATURES, the classpath, and
 * other files. So reuse is allowed only when imports are unchanged, no cross-file dependency changed (the
 * caller's external stamp), and the single changed declaration (if any) changed only its BODY (its
 * header/signature is unchanged) — a body edit can't alter what any other declaration resolves against. Any
 * other shape (≥2 changes, a signature change, an added/removed/reordered declaration, an import edit) → full.
 */
internal object IncrementalDecls {

    /** A cached declaration's change-detection key: its signature [header] and full [fullText]. */
    class Key(val header: String, val fullText: String)

    /** Build a [Key] for [d] (materializes its text once). Call only when (re)caching a recomputed declaration. */
    fun keyOf(d: KtDeclaration): Key {
        val t = d.text
        val header = (d as? KtNamedFunction)?.bodyBlockExpression
            ?.let { t.substring(0, it.textRange.startOffset - d.textRange.startOffset) } ?: t
        return Key(header, t)
    }

    /** The package + import surface; a change here can alter resolution in every declaration → full recompute. */
    fun importsKey(ktFile: KtFile): String =
        (ktFile.packageDirective?.text ?: "") + " " + (ktFile.importList?.text ?: "")

    /**
     * Indices of top-level declarations to recompute against [prev] (index-aligned), or null to recompute the
     * whole file. Empty list → nothing changed (reuse all). Exactly one index → a body-only function edit
     * (safe; see the class doc). [prevImportsKey] is the import surface at cache time.
     */
    fun recomputeIndices(prev: List<Key>?, prevImportsKey: String?, importsKey: String, topDecls: List<KtDeclaration>): List<Int>? {
        if (prev == null || prevImportsKey != importsKey || prev.size != topDecls.size) return null
        val changed = ArrayList<Int>(2)
        for (i in topDecls.indices) if (topDecls[i].text != prev[i].fullText) changed += i
        if (changed.size > 1) return null // a multi-declaration edit → don't reason about it; recompute fully
        if (changed.isEmpty()) return changed // identical text (e.g. a caret-only re-run) → reuse everything
        val k = changed[0]
        val fn = topDecls[k] as? KtNamedFunction ?: return null
        val body = fn.bodyBlockExpression ?: return null // expression bodies feed inference → treat as structural
        val newHeader = fn.text.substring(0, body.textRange.startOffset - fn.textRange.startOffset)
        return if (newHeader == prev[k].header) changed else null // header (signature) changed → full
    }
}
