package dev.ide.lang.kotlin

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.DiagnosticReporter
import dev.ide.lang.kotlin.analysis.KotlinAnalysisSession
import dev.ide.lang.kotlin.analysis.KotlinCheckerDriver
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.typeAliasNamesIn
import dev.ide.lang.kotlin.resolve.*
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
internal class IncrementalSemanticAnalysis(
    private val service: KotlinSymbolService,
    /** Supplies the per-snapshot memo caches shared with the Compose preview lowerer, so the two passes a
     *  keystroke runs over the same snapshot don't each recompute inference/overload resolution cold. Null →
     *  a private cache per pass (standalone use / tests). */
    private val cachesFor: ((KotlinParsedFile) -> KotlinResolverCaches)? = null,
) {

    private val checks = KotlinSemanticChecks(service)

    // The read-only checker set, driven over each declaration's subtree in one walk (see KotlinCheckerDriver).
    // Built once: the checkers are stateless beyond the per-pass caches [checks] clears in resetPassCaches.
    private val driver = KotlinCheckerDriver(checks.checkers())

    // Per-file incremental-analyze cache. The semantic pass re-resolves the whole file, which is the editor's
    // dominant cost on a large file; this lets a body-only edit re-analyze ONLY the changed function and reuse
    // every other declaration's diagnostics (re-anchored to its shifted offset). See [diagnostics].
    // One top-level body statement's expensive (resolution) diagnostics, relative to the STATEMENT's start.
    // [declares] = the statement introduces a name (a later statement's scope depends on it), so a change to it
    // invalidates the reuse of everything after it.
    private class StmtDiags(val text: String, val declares: Boolean, val rel: List<Diagnostic>)
    // [bodyStmts] is present for a block-bodied function — it enables INTRA-function reuse (a keystroke in a
    // 150-line @Composable re-checks only the touched statement, not all ~50 calls). Null for other declarations.
    // [facts] carries the declaration's change-detection key plus its dependency surface (provided/referenced
    // names) so [IncrementalDecls.plan] can invalidate only the declarations a change actually affects.
    private class DeclDiags(val facts: IncrementalDecls.Facts, val rel: List<Diagnostic>, val bodyStmts: List<StmtDiags>? = null)
    // [externalStamp] = the content versions of OTHER source files at cache time ([KotlinSymbolService.
    // externalContentStamp]); when it changes, a cross-file dependency was edited, so this file's cached
    // diagnostics may be stale (e.g. a class whose property type changed in another file) → full re-analyze.
    // [fileText]/[imports] are the full text + import surface at cache time, for the next pass's change diff.
    private class AnalyzeCache(
        val imports: IncrementalDecls.Imports,
        val fileText: String,
        val externalStamp: Long,
        val decls: List<DeclDiags>,
    )
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
     * Incremental reuse is driven by [IncrementalDecls.plan]: the edited span is located by a text diff, and
     * only the declarations it can affect are re-analyzed (the changed ones, plus the dependents of any
     * signature or import change), with every other declaration's cached diagnostics reused re-anchored to its
     * shifted offset. A single body-only function edit further reuses the unchanged statements WITHIN it
     * ([analyzeFunctionBody]). A structural change (declaration added/removed/reordered), a package or
     * star-import change, or a symbolic-operator signature change can't be scoped soundly → full re-analyze.
     * A cross-file edit ([externalStamp]) still triggers a full re-analyze (per-declaration cross-file scoping
     * is a later phase).
     */
    fun diagnostics(parsed: KotlinParsedFile): List<Diagnostic> {
        checks.resetPassCaches() // PSI-keyed per-pass caches must not survive the reparse
        val ktFile = parsed.ktFile
        val caches = cachesFor?.invoke(parsed)
        val resolver = if (caches != null) {
            KotlinResolver(ktFile, parsed, service, caches)
        } else {
            KotlinResolver(ktFile, parsed, service)
        }

        // same-file typealiases (the disk model may lag the buffer)
        val localAliases = typeAliasNamesIn(ktFile)
        // The read-only session shared by every checker this pass. `resolveReady` is read once here (it can't
        // flip mid-pass on the single engine thread), gating the classpath-dependent checks in "dumb mode".
        val session = KotlinAnalysisSession(resolver, service, localAliases, service.classpathReady())
        val fileLevel = KotlinPerf.span("fileLevel") { checks.fileLevelDiagnostics(ktFile) }

        val topDecls = ktFile.declarations
        val curImports = IncrementalDecls.importsOf(ktFile)
        val curFileText = ktFile.text
        // A cross-file dependency changed (a class edited in ANOTHER file) → this file's cached diagnostics may
        // be stale even though its own text is unchanged → re-analyze fully. (Self-edits are excluded from the
        // stamp and scoped by [IncrementalDecls.plan] below; per-declaration cross-file scoping is a later phase.)
        val externalStamp = service.externalContentStamp(parsed.file.path)
        val prev = analyzeCache[parsed.file.path]?.takeIf { it.externalStamp == externalStamp }
        // The recompute plan: the declarations this edit can actually affect (the changed ones plus the
        // dependents of any signature/import change). Everything else is reused, re-anchored. See IncrementalDecls.
        val plan = KotlinPerf.span("scopeCheck") {
            IncrementalDecls.plan(prev?.decls?.map { it.facts }, prev?.imports, prev?.fileText, topDecls, curImports, curFileText)
        }
        val recompute: Set<Int>? = (plan as? IncrementalDecls.Plan.Partial)?.recompute // null → full recompute
        val fineReuseIndex: Int? = (plan as? IncrementalDecls.Plan.Partial)?.fineReuse

        val perDecl = ArrayList<List<Diagnostic>>(topDecls.size)
        val newEntries = ArrayList<DeclDiags>(topDecls.size)
        KotlinPerf.span("walk") { for ((i, d) in topDecls.withIndex()) {
            val base = d.textRange.startOffset
            if (recompute != null && i !in recompute) {
                val cached = prev!!.decls[i] // unaffected → reuse, re-anchored to the (possibly shifted) offset
                perDecl += cached.rel.map { it.copy(range = TextRange(it.range.start + base, it.range.end + base)) }
                newEntries += cached
            } else {
                // Recompute this declaration. A block-bodied function reuses its unchanged statements ONLY when it
                // is the single body-only change ([fineReuseIndex]); a dependent (re-checked because a sibling's
                // signature moved) has unchanged text but changed resolution, so it re-checks fully. Either way it
                // records per-statement entries so the NEXT edit can reuse them.
                val fn = d as? KtNamedFunction
                if (fn != null && fn.bodyBlockExpression != null) {
                    val prevEntry = if (fineReuseIndex == i) prev?.decls?.getOrNull(i) else null
                    val (diags, stmts) = analyzeFunctionBody(fn, prevEntry, session)
                    perDecl += diags
                    newEntries += DeclDiags(IncrementalDecls.factsOf(d), diags.map { it.copy(range = TextRange(it.range.start - base, it.range.end - base)) }, stmts)
                } else {
                    val reporter = DiagnosticReporter()
                    driver.run(d, session, reporter)
                    val diags = reporter.drain()
                    perDecl += diags
                    newEntries += DeclDiags(IncrementalDecls.factsOf(d), diags.map { it.copy(range = TextRange(it.range.start - base, it.range.end - base)) })
                }
            }
        } }
        analyzeCache[parsed.file.path] = AnalyzeCache(curImports, curFileText, externalStamp, newEntries)

        val result = ArrayList<Diagnostic>(fileLevel)
        perDecl.forEach { result += it }
        return result
    }

    /**
     * Analyze one block-bodied function, reusing the per-statement diagnostics of unchanged top-level body
     * statements (against [prev]). Returns the function's diagnostics (absolute offsets) plus the per-statement
     * cache to store for next time. Equivalent to a full driver walk of the whole function, verified by
     * `KotlinIncrementalAnalyzeTest` (incremental == full).
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
        fn: KtNamedFunction, prev: DeclDiags?, session: KotlinAnalysisSession,
    ): Pair<List<Diagnostic>, List<StmtDiags>> {
        val body = fn.bodyBlockExpression!!
        val statements = body.statements
        val out = ArrayList<Diagnostic>()
        // Frame: walk the function but stop at each top-level statement (handled per-statement below); skip the
        // cross-statement local checks (done next). This still runs the block-node checks (duplicate/unreachable).
        KotlinPerf.span("frame") {
            val reporter = DiagnosticReporter()
            driver.run(fn, session, reporter, stopAt = statements.toHashSet(), skipCrossStatement = true)
            out += reporter.drain()
        }
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
                prevS.rel.forEach { out += it.copy(range = TextRange(it.range.start + sBase, it.range.end + sBase)) }
                newStmts += prevS
            } else {
                val reporter = DiagnosticReporter()
                driver.run(st, session, reporter, skipCrossStatement = true)
                val sd = reporter.drain()
                out += sd
                newStmts += StmtDiags(text, declares, sd.map { it.copy(range = TextRange(it.range.start - sBase, it.range.end - sBase)) })
            }
            // A changed declaration alters the scope every later statement resolves against → recompute the rest.
            if (changed && declares) scopeDirty = true
        }
        return out to newStmts
    }
}
