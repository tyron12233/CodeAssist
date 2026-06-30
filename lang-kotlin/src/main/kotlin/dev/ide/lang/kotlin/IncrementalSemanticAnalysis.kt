package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.typeAliasNamesIn
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the Kotlin [KotlinSemanticChecks] over a file and caches per-declaration results so an edit re-checks
 * only what changed. One instance per analyzer (the cache is its state). Separated from the checks
 * themselves: this owns the incremental-reuse machinery (text hashing, offset re-anchoring, header
 * comparison), the checks own what each diagnostic means.
 */
internal class IncrementalSemanticAnalysis(private val service: KotlinSymbolService) {

    private val checks = KotlinSemanticChecks(service)

    // Per-file incremental-analyze cache. The semantic pass re-resolves the whole file, which is the editor's
    // dominant cost on a large file; this lets a body-only edit re-analyze ONLY the changed function and reuse
    // every other declaration's diagnostics (re-anchored to its shifted offset). See [diagnostics].
    // One top-level body statement's expensive (resolution) diagnostics, relative to the STATEMENT's start.
    // [declares] = the statement introduces a name (a later statement's scope depends on it), so a change to it
    // invalidates the reuse of everything after it.
    private class StmtDiags(val text: String, val declares: Boolean, val rel: List<Diagnostic>)
    // [bodyStmts] is present for a block-bodied function — it enables INTRA-function reuse (a keystroke in a
    // 150-line @Composable re-checks only the touched statement, not all ~50 calls). Null for other declarations.
    private class DeclDiags(val header: String, val fullText: String, val rel: List<Diagnostic>, val bodyStmts: List<StmtDiags>? = null)
    // [externalStamp] = the content versions of OTHER source files at cache time ([KotlinSymbolService.
    // externalContentStamp]); when it changes, a cross-file dependency was edited, so this file's cached
    // diagnostics may be stale (e.g. a class whose property type changed in another file) → full re-analyze.
    private class AnalyzeCache(val importsKey: String, val externalStamp: Long, val decls: List<DeclDiags>)
    private val analyzeCache = ConcurrentHashMap<String, AnalyzeCache>()

    /**
     * Semantic diagnostics, computed incrementally. Conservative to avoid false positives over an incomplete
     * (parse-only) symbol model. It flags:
     *  - an unresolved member on an explicit receiver whose type was inferred (`"".bogus()`), including an
     *    extension that is on the classpath but not in scope (an unimported `16.dp`/`14.sp`);
     *  - an unresolved bare reference: a lower-case name in value position that resolves to nothing;
     *  - a named argument whose name matches no parameter of any function the call could resolve to;
     *  - a type mismatch for an initializer (`val a: Int = ""`) or a `return` value (`fun f(): Int { return "" }`),
     *    including a value returned from a `Unit` function (`fun f() { return 5 }`);
     *  - a useless cast (`x as String` where `x` is already `String`) and a useless elvis (`x!! ?: y`);
     *  - conflicting/repeated modifiers (`final open`, `private public`, `open open`);
     *  - a `val` reassignment (`val x = 1; x = 2`);
     *  - a missing `return` in a block-body function with a non-Unit declared return type;
     *  - conflicting declarations in one scope (duplicate names / same-signature functions);
     *  - an argument-count mismatch for a call to a same-file function (where arity is readable from PSI);
     *  - a missing initializer on a top-level / concrete-class property;
     *  - a `val`/`var` with no type AND no initializer/delegate/accessor (`val test`);
     *  - misuse of `lateinit` (on a `val`, with an initializer/delegate, or a nullable type);
     *  - misuse of `abstract` (a body/initializer on an abstract member, or one in a non-abstract class);
     *  - `val`/`var` on a parameter outside a primary constructor (`fun f(val x: Int)`);
     *  - an unsafe nullable access (`s.length` where `s: String?`, no guard);
     *  - unused imports, `private` declarations, and locals, and a `var` that could be `val` (warnings/hints).
     * Capitalized names (types/constructors), type-position references (generic params), qualified receivers,
     * numeric-literal adaptation, `Nothing` terminals, smart-cast guards, and implicit-companion bodies are left alone.
     *
     * The set is `file-level checks` + `Σ per top-level declaration`. Walking the package/import subtrees yields
     * nothing (the per-node checks back off inside imports), so this is equivalent to one whole-file walk.
     * Incremental reuse: when exactly one top-level declaration changed AND it is a block-bodied function whose
     * HEADER (modifiers/annotations/receiver/params/return type) is unchanged, only its body changed — nothing
     * any OTHER declaration resolves against moved (a block body's return type is declared, so it's part of the
     * header), and imports are unchanged (guarded). So that one function is re-analyzed and every other
     * declaration's cached diagnostics are reused, re-anchored to its new start offset. Any other shape
     * (signature change, added/removed/reordered declaration, multiple changes, an import edit) → full re-analyze.
     */
    fun diagnostics(parsed: KotlinParsedFile): List<Diagnostic> {
        checks.resetPassCaches() // PSI-keyed per-pass caches must not survive the reparse
        val ktFile = parsed.ktFile
        val resolver = KotlinResolver(ktFile, parsed, service)
        val localAliases = typeAliasNamesIn(ktFile) // same-file typealiases (the disk model may lag the buffer)

        val fileLevel = KotlinPerf.span("fileLevel") { checks.fileLevelDiagnostics(ktFile) }

        val topDecls = ktFile.declarations
        val importsKey = (ktFile.packageDirective?.text ?: "") + "\u0000" + (ktFile.importList?.text ?: "")
        val externalStamp = service.externalContentStamp(parsed.file.path)
        // A cross-file dependency changed (a class edited in ANOTHER file) → this file's cached diagnostics may
        // be stale even though its own text is unchanged → re-analyze fully. (Self-edits are excluded from the
        // stamp and handled by the per-declaration text diff in recomputeIndices.)
        val prev = analyzeCache[parsed.file.path]?.takeIf { it.externalStamp == externalStamp }
        val recompute = KotlinPerf.span("scopeCheck") { recomputeIndices(prev, importsKey, topDecls) } // null → full

        val perDecl = ArrayList<List<Diagnostic>>(topDecls.size)
        val newEntries = ArrayList<DeclDiags>(topDecls.size)
        KotlinPerf.span("walk") { for ((i, d) in topDecls.withIndex()) {
            val base = d.textRange.startOffset
            if (recompute != null && i !in recompute) {
                val cached = prev!!.decls[i] // text identical → reuse, re-anchored to the (possibly shifted) offset
                perDecl += cached.rel.map { it.copy(range = TextRange(it.range.start + base, it.range.end + base)) }
                newEntries += cached
            } else {
                // A body-only edit to a single block-bodied function (header + imports unchanged, guaranteed by
                // [recomputeIndices]) → reuse the unchanged statements WITHIN it. `prev` is index-aligned only in
                // that single-change case, so intra-function reuse is enabled there alone; a full re-analyze still
                // records per-statement entries so the NEXT edit can reuse them.
                val fn = d as? KtNamedFunction
                if (fn != null && fn.bodyBlockExpression != null) {
                    val fineReuse = recompute != null && recompute.size == 1 && recompute[0] == i
                    val (diags, stmts) = analyzeFunctionBody(fn, if (fineReuse) prev?.decls?.getOrNull(i) else null, resolver, localAliases)
                    perDecl += diags
                    newEntries += DeclDiags(headerOf(d), d.text, diags.map { it.copy(range = TextRange(it.range.start - base, it.range.end - base)) }, stmts)
                } else {
                    val diags = ArrayList<Diagnostic>()
                    checks.walkDecl(d, resolver, localAliases, diags)
                    perDecl += diags
                    newEntries += DeclDiags(headerOf(d), d.text, diags.map { it.copy(range = TextRange(it.range.start - base, it.range.end - base)) })
                }
            }
        } }
        analyzeCache[parsed.file.path] = AnalyzeCache(importsKey, externalStamp, newEntries)

        val result = ArrayList<Diagnostic>(fileLevel)
        perDecl.forEach { result += it }
        return result
    }

    /**
     * Which top-level declarations must be re-analyzed against [prev], or null to re-analyze the whole file.
     * Empty list → nothing changed (reuse all). One index → a safe body-only function edit (see
     * [diagnostics]). Anything else → null (full).
     */
    private fun recomputeIndices(prev: AnalyzeCache?, importsKey: String, topDecls: List<KtDeclaration>): List<Int>? {
        if (prev == null || prev.importsKey != importsKey || prev.decls.size != topDecls.size) return null
        val changed = ArrayList<Int>(2)
        for (i in topDecls.indices) if (topDecls[i].text != prev.decls[i].fullText) changed += i
        if (changed.size > 1) return null // a multi-declaration edit → don't reason about it; re-analyze fully
        if (changed.isEmpty()) return changed // identical text (e.g. a caret-only re-analyze) → reuse everything
        val k = changed[0]
        val fn = topDecls[k] as? KtNamedFunction ?: return null
        val body = fn.bodyBlockExpression ?: return null // expression bodies feed inference → treat as structural
        val newHeader = fn.text.substring(0, body.textRange.startOffset - fn.textRange.startOffset)
        return if (newHeader == prev.decls[k].header) changed else null // header changed → signature changed → full
    }

    /** A declaration's pre-body text (signature surface) for a block-bodied function; its full text otherwise. */
    private fun headerOf(d: KtDeclaration): String {
        val body = (d as? KtNamedFunction)?.bodyBlockExpression ?: return d.text
        return d.text.substring(0, body.textRange.startOffset - d.textRange.startOffset)
    }

    /**
     * Analyze one block-bodied function, reusing the per-statement diagnostics of unchanged top-level body
     * statements (against [prev]). Returns the function's diagnostics (absolute offsets) plus the per-statement
     * cache to store for next time. Equivalent to [KotlinSemanticChecks.walkDecl] of the whole function —
     * verified by `KotlinIncrementalAnalyzeTest` (incremental == full).
     *
     * Three parts, composed so the result is identical to a full walk:
     *  - **frame** — the function node + header + the body-block-level checks (missing-return, param/return
     *    types, duplicate/unreachable across statements), walked fresh each edit but excluding the statement
     *    subtrees. Cheap (no per-call overload resolution lives here).
     *  - **local-declaration checks** — unused-local / var-could-be-val over the whole body (they read sibling
     *    statements, so they can't be cached per statement).
     *  - **per-statement** — each top-level body statement's resolution diagnostics, REUSED when its text is
     *    unchanged AND no earlier statement that declares a name changed (so its visible scope is identical).
     */
    private fun analyzeFunctionBody(
        fn: KtNamedFunction, prev: DeclDiags?, resolver: KotlinResolver, localAliases: Set<String>,
    ): Pair<List<Diagnostic>, List<StmtDiags>> {
        val body = fn.bodyBlockExpression!!
        val statements = body.statements
        val out = ArrayList<Diagnostic>()
        // Frame: walk the function but stop at each top-level statement (handled per-statement below); skip the
        // cross-statement local checks (done next). This still runs the block-node checks (duplicate/unreachable).
        KotlinPerf.span("frame") { checks.walkDecl(fn, resolver, localAliases, out, stopAt = statements.toHashSet(), skipLocalDeclChecks = true) }
        KotlinPerf.span("localDecl") { checks.localDeclarationChecks(fn, out) }

        val prevStmts = prev?.bodyStmts
        // A structural change (statement added/removed/reordered) → no index alignment, recompute every statement.
        var scopeDirty = prevStmts == null || prevStmts.size != statements.size
        val newStmts = ArrayList<StmtDiags>(statements.size)
        for ((i, st) in statements.withIndex()) {
            val sBase = st.textRange.startOffset
            val text = st.text
            val declares = st is KtDeclaration
            val prevS = prevStmts?.getOrNull(i)
            val changed = prevS == null || prevS.text != text
            if (!changed && !scopeDirty) {
                prevS!!.rel.forEach { out += it.copy(range = TextRange(it.range.start + sBase, it.range.end + sBase)) }
                newStmts += prevS
            } else {
                val sd = ArrayList<Diagnostic>()
                checks.walkDecl(st, resolver, localAliases, sd, skipLocalDeclChecks = true)
                out += sd
                newStmts += StmtDiags(text, declares, sd.map { it.copy(range = TextRange(it.range.start - sBase, it.range.end - sBase)) })
            }
            // A changed declaration alters the scope every later statement resolves against → recompute the rest.
            if (changed && declares) scopeDirty = true
        }
        return out to newStmts
    }
}
