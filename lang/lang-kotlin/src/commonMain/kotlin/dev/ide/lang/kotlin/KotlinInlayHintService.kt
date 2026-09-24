package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.lang.dom.TextRange
import dev.ide.lang.hints.InlayHint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.lang.hints.InlayHintPart
import dev.ide.lang.hints.InlayHintService
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.resolve.*
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.platform.ConcurrentMap
import dev.ide.platform.EngineCancellation
import dev.ide.vfs.VirtualFile

/**
 * Inlay hints for Kotlin, computed over the live PSI + the backend's own inference (no FIR). Mirrors the
 * Java/JDT hints but for the cases that matter in Kotlin:
 *  - **local `val`/`var` inferred types** — `val x = foo()` → `x: Bar` (only when there's no explicit type),
 *  - **lambda parameter types** — `list.map { x -> … }` → `x: String`, and the implicit `it` → `it: String`,
 *  - **lambda scope receivers** — `Column { … }` → `this: ColumnScope`, for any receiver-typed lambda
 *    (`RowScope.() -> Unit`, a DSL builder block, `with(x) { … }`),
 *  - **parameter names**: at a call site, the name of each parameter in front of a literal argument
 *    (`setPadding(/*left:*/ 0, …)`), which is where a bare `0, 0, 8, 0` says least about what it means.
 *
 * Every hint comes from a type the resolver could infer; an unknown type simply yields no hint (so a
 * half-typed buffer never shows a wrong or `Unknown` annotation).
 */
class KotlinInlayHintService(
    private val parsedFor: (VirtualFile) -> KotlinParsedFile?,
    private val resolverFor: (KotlinParsedFile) -> KotlinResolver,
    /** The cross-file content stamp for a path: a change means another file this one resolves against moved. */
    private val externalStampFor: (String) -> Long = { 0L },
) : InlayHintService {

    /** One top-level declaration's hints, relative to its start; null until the declaration was in a window. */
    private class DeclHints(val facts: IncrementalDecls.Facts, val rel: List<InlayHint>?)

    private class Snapshot(
        val imports: IncrementalDecls.Imports,
        val fileText: String,
        val externalStamp: Long,
        val decls: List<DeclHints>,
    )

    private val cache = ConcurrentMap<String, Snapshot>()

    /** Drop [path]'s cached hints; its next request recomputes them. */
    fun forget(path: String) {
        cache.remove(path)
    }

    /** Drop every file's cached hints (memory pressure). */
    fun clear() = cache.clear()

    /**
     * Hints for [range], incremental per top-level declaration like the highlighter and the diagnostics: a
     * declaration the edit cannot affect (the shared [IncrementalDecls] plan) keeps its hints, re-anchored, and
     * only the changed ones and their dependents infer again. Inferring a type per `val` and lambda is the cost
     * of this pass, and every keystroke used to redo it for the whole window.
     */
    override suspend fun hints(file: VirtualFile, range: TextRange): List<InlayHint> {
        val parsed = parsedFor(file) ?: return emptyList()
        val resolver = resolverFor(parsed)
        val ktFile = parsed.ktFile
        val topDecls = ktFile.declarations
        val curImports = IncrementalDecls.importsOf(ktFile)
        val curFileText = ktFile.text
        val externalStamp = externalStampFor(file.path)
        val prev = cache[file.path]?.takeIf { it.externalStamp == externalStamp }
        val plan = IncrementalDecls.plan(
            prev?.decls?.map { it.facts }, prev?.imports, prev?.fileText, topDecls, curImports, curFileText,
        )
        val recompute: Set<Int>? = (plan as? IncrementalDecls.Plan.Partial)?.recompute
        val out = ArrayList<InlayHint>()
        val entries = ArrayList<DeclHints>(topDecls.size)
        for ((i, d) in topDecls.withIndex()) {
            val base = d.textRange.startOffset
            val end = base + d.textLength
            val inWindow = end >= range.start && base <= range.end
            val reusable = recompute != null && i !in recompute
            val cached = if (reusable) prev!!.decls[i].rel else null
            // A declaration far larger than the window (a class that is the whole file) is walked only where
            // it meets the window, as before, and left uncached: computing all of it to cache it would cost
            // more per edit than the window it serves.
            val oversized = end - base > OVERSIZED_FACTOR * (range.end - range.start + 1)
            val rel = when {
                cached != null -> cached
                inWindow && oversized -> {
                    walk(d, resolver, out, range)
                    null
                }
                inWindow -> {
                    // A whole declaration at a time, so the entry is complete for any later window.
                    val abs = ArrayList<InlayHint>()
                    walk(d, resolver, abs, null)
                    abs.map { it.copy(offset = it.offset - base) }
                }
                else -> null // off screen and not reusable: computed when it is next in a window
            }
            // A reusable declaration's text is unchanged, so its facts are too; walking it for them again would
            // read every body in the file on each edit.
            val facts = if (reusable) prev!!.decls[i].facts else IncrementalDecls.factsOf(d)
            entries += DeclHints(facts, rel)
            if (inWindow && rel != null) for (h in rel) out += h.copy(offset = h.offset + base)
        }
        cache[file.path] = Snapshot(curImports, curFileText, externalStamp, entries)
        return out.filter { it.offset in range.start..range.end }.sortedBy { it.offset }
    }

    /** Collect [psi]'s hints; with a [window], only from the subtrees that intersect it. */
    private fun walk(psi: KtElement, resolver: KotlinResolver, out: MutableList<InlayHint>, window: TextRange?) {
        if (window != null) {
            val start = psi.textOffset
            if (start + psi.textLength < window.start || start > window.end) return
        }
        // Between nodes, never inside one resolution: a completion preempts the pass here.
        EngineCancellation.checkCanceled()
        when (psi) {
            is KtProperty -> localTypeHint(psi, resolver)?.let { out += it }
            is KtLambdaExpression -> lambdaHints(psi, resolver, out)
            is KtCallExpression -> parameterNameHints(psi, resolver, out)
            else -> {}
        }
        var c = psi.firstChild
        while (c != null) { walk(c, resolver, out, window); c = c.nextSibling }
    }

    /** A LOCAL `val`/`var` with an initializer but NO explicit type → the inferred type after its name. */
    private fun localTypeHint(prop: KtProperty, resolver: KotlinResolver): InlayHint? {
        if (prop.parent !is KtBlockExpression) return null // locals only (members are usually explicitly typed)
        if (prop.typeReference != null) return null // already has a written type
        val init = prop.initializer ?: return null
        val nameEnd = prop.nameIdentifier?.textRange?.endOffset ?: return null
        val type = resolver.inferType(init) ?: return null
        return typeHint(nameEnd, type)
    }

    /** Untyped lambda parameters (`{ x -> }`), the implicit `it`, and the implicit `this` receiver of a
     *  receiver-typed lambda, all typed from the function-type the lambda fills. */
    private fun lambdaHints(lambda: KtLambdaExpression, resolver: KotlinResolver, out: MutableList<InlayHint>) {
        // Added first so it sorts ahead of any same-offset `it:` hint (`R.(A) -> Unit` shows `this: R it: A`).
        receiverScopeHint(lambda, resolver)?.let { out += it }

        val inputs = resolver.lambdaParameterTypes(lambda)
        if (inputs.isEmpty()) return
        val params = lambda.valueParameters
        if (params.isEmpty()) {
            // Implicit `it`: only when the lambda takes one value and the body actually references `it`.
            val it = inputs.singleOrNull() as? KotlinType ?: return
            if (!referencesIt(lambda)) return
            val lBrace = lambda.functionLiteral.lBrace // always present on a parsed lambda
            out += InlayHint(
                lBrace.textRange.endOffset,
                listOf(InlayHintPart("it: $it"), InlayHintPart(" ->")),
                InlayHintKind.TYPE, tooltip = it.qualifiedName, paddingLeft = true, paddingRight = true,
            )
        } else {
            params.forEachIndexed { i, p ->
                if (p.typeReference != null) return@forEachIndexed // already typed
                val t = inputs.getOrNull(i) as? KotlinType ?: return@forEachIndexed
                val nameEnd = p.nameIdentifier?.textRange?.endOffset ?: return@forEachIndexed
                out += typeHint(nameEnd, t)
            }
        }
    }

    /** A receiver-typed lambda (`RowScope.() -> Unit` — a Compose content lambda, a DSL builder block,
     *  `with`/`buildString`) → `this: RowScope` right after the `{`. Suppressed when the receiver is an
     *  unbound type parameter (e.g. `apply`/`run`, whose `T` is only known from the call's receiver, which the
     *  resolver doesn't bind) so we never render a bare `this: T`. */
    private fun receiverScopeHint(lambda: KtLambdaExpression, resolver: KotlinResolver): InlayHint? {
        val receiver = resolver.lambdaReceiverType(lambda) ?: return null
        if (receiver.isTypeParameter) return null
        val lBrace = lambda.functionLiteral.lBrace // always present on a parsed lambda
        return InlayHint(
            lBrace.textRange.endOffset,
            listOf(InlayHintPart("this: " + receiver)),
            InlayHintKind.TYPE, tooltip = receiver.qualifiedName, paddingLeft = true, paddingRight = true,
        )
    }

    private fun referencesIt(lambda: KtLambdaExpression): Boolean {
        var found = false
        fun rec(p: KtElement) {
            if (found) return
            // A nested lambda shadows `it`, so don't look inside one.
            if (p is KtLambdaExpression && p !== lambda) return
            if (p is KtNameReferenceExpression && p.getReferencedName() == "it") { found = true; return }
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        lambda.bodyExpression?.let { rec(it) }
        return found
    }

    private fun typeHint(offset: Int, type: KotlinType): InlayHint =
        InlayHint(offset, listOf(InlayHintPart(": " + type)), InlayHintKind.TYPE, tooltip = type.qualifiedName, paddingLeft = false)

    /**
     * `setPadding(0, 8, 0, 8)` → `setPadding(left: 0, top: 8, …)`. Only LITERAL arguments are annotated: a
     * named variable already reads as its own documentation, and hinting every argument turns a call into
     * noise. Same rule as the Java side, so the two editors agree on when a hint is worth showing.
     *
     * This is where a Java method's real parameter names surface in a Kotlin file, so it depends on the two
     * things that recover them for a binary callee: the `MethodParameters` attribute
     * ([dev.ide.lang.kotlin.symbols.JavaBytecode]) and the attached-source enrichment
     * ([dev.ide.lang.kotlin.symbols.KotlinSymbolService]). When neither can, the name is a synthetic `p0`
     * and no hint is emitted (`p0: 8` teaches nothing).
     */
    private fun parameterNameHints(
        call: KtCallExpression,
        resolver: KotlinResolver,
        out: MutableList<InlayHint>
    ) {
        // Resolving a callee is the expensive step and most calls take no literal at all, so the cheap
        // syntactic test runs FIRST, since this walk covers every call in the viewport on each render.
        val args = call.valueArguments.filterIsInstance<KtValueArgument>()
        if (args.none { it.getArgumentName() == null && isLiteralLike(it.getArgumentExpression()) }) return
        // calleeFunctionOf picks the single best overload but only ever considers FUNCTIONS, so a constructor
        // call (`Point(1, 2)`) falls through to the full target set, which is an overload set, hence the
        // agreement rule in [agreedParamName].
        val candidates = listOfNotNull(resolver.calleeFunctionOf(call)).ifEmpty { resolver.callTargets(call) }
        if (candidates.isEmpty()) return

        args.forEachIndexed { i, arg ->
            if (arg.getArgumentName() != null) return@forEachIndexed // `left = 0` already names itself
            val expr = arg.getArgumentExpression() ?: return@forEachIndexed
            if (!isLiteralLike(expr)) return@forEachIndexed
            val name = agreedParamName(candidates, arg, i, resolver) ?: return@forEachIndexed
            out += InlayHint(
                expr.textRange.startOffset,
                listOf(InlayHintPart("$name:")),
                InlayHintKind.PARAMETER,
                paddingRight = true,
            )
        }
    }

    /**
     * The name every candidate gives the parameter this argument fills, or null when they disagree, when none
     * can name it, or when any of them takes a vararg there (one vararg name would repeat down the whole
     * trailing run). Unresolved overloads are the normal case for a constructor call, and a hint that names
     * the wrong parameter is worse than no hint at all.
     */
    private fun agreedParamName(
        candidates: List<KotlinSymbol>,
        arg: KtValueArgument,
        argIndex: Int,
        resolver: KotlinResolver,
    ): String? {
        val names = HashSet<String>()
        for (c in candidates) {
            val paramIndex = resolver.argParamIndex(arg, argIndex, c)
            if (paramIndex == c.varargParamIndex) return null
            val n = c.paramNames.getOrNull(paramIndex) ?: continue
            if (n.isEmpty() || resolver.isSyntheticParamName(n)) continue
            names += n
        }
        return names.singleOrNull()
    }

    /** A literal (`0`, `"s"`, `true`, `null`), optionally signed (`-1`): an argument whose text carries no
     *  hint of what it means. A `KtConstantExpression` covers numbers/booleans/`null`. */
    private fun isLiteralLike(e: KtExpression?): Boolean = when (e) {
        is KtConstantExpression -> true
        is KtStringTemplateExpression -> true
        is KtPrefixExpression -> isLiteralLike(e.baseExpression)
        else -> false
    }
}

/** How many times larger than the requested window a changed declaration may be and still be hinted whole. */
private const val OVERSIZED_FACTOR = 2
