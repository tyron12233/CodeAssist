package dev.ide.lang.kotlin

import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Per-top-level-declaration incremental-reuse machinery for the editor passes that are "Σ over the file's
 * top-level declarations" (semantic diagnostics via [IncrementalSemanticAnalysis], semantic highlighting via
 * [KotlinSemanticHighlighter]): cache each declaration's results and, on the next edit, recompute only the
 * declaration(s) whose result could have changed, reusing the rest re-anchored to their shifted offsets.
 *
 * A declaration's result depends on its own subtree PLUS the symbols visible to it: imports, the SIGNATURES
 * of sibling top-level declarations, the classpath, and other files. [plan] uses that dependency structure
 * instead of recomputing the whole file whenever anything changes:
 *  - The edited span is located by a prefix/suffix text diff ([changedRange]) so only the declaration(s) it
 *    overlaps are text-compared; everything outside is byte-identical and reused (this is the platform-
 *    independent substitute for the desktop-only incremental PSI reparse).
 *  - A declaration whose only change is its BODY re-checks itself alone (its signature is unchanged, so
 *    nothing else resolves differently).
 *  - A declaration whose SIGNATURE changed (or a whole class edit) re-checks itself plus the declarations
 *    that REFERENCE a name it [provides][Facts.provides] (its dependents), found via each cached declaration's
 *    [references][Facts.references] set. Unrelated declarations are reused.
 *  - An import edit re-checks only the declarations referencing the added/removed names; a star-import change
 *    or a package change can't be scoped, so it falls back to a full recompute.
 * The reference/provide sets are over-approximated by simple name, so [plan] is SOUND (it can only recompute
 * too much, never too little); when in doubt it returns [Plan.Full]. Cross-file staleness is handled by the
 * caller's external stamp (a separate concern; see [IncrementalSemanticAnalysis]).
 *
 * Change detection is by TEXT, not PSI identity: the incremental reparse keeps the SAME composite PsiElement
 * for a top-level declaration even when its children change, so `decl === cached` is true for a CHANGED
 * declaration too. A text compare is the sound signal and (narrowed to the edited span) cheap.
 */
internal object IncrementalDecls {

    /** A cached declaration's change-detection key: its signature [header] and full [fullText]. */
    class Key(val header: String, val fullText: String)

    /**
     * A cached declaration's dependency facts: its change-detection [key], the names it [provides] (its own
     * name plus, for a class/object, its member names, since a depender references those), and the names it
     * [references] (every simple name in its subtree, over-approximated). [references] drives dependent lookup.
     */
    class Facts(val key: Key, val provides: Set<String>, val references: Set<String>)

    /** The import surface, compared to decide whether (and how narrowly) an import edit invalidates. */
    class Imports(val packageText: String, val names: Set<String>, val starPackages: Set<String>)

    /** Recompute plan for a keystroke: [Full] recompute, or [Partial] naming exactly the indices to recompute. */
    sealed interface Plan {
        object Full : Plan

        /**
         * Recompute the declarations at [recompute] (reuse all others, re-anchored). [fineReuse], when non-null,
         * is the single body-only-changed function eligible for intra-function statement reuse; the rest of
         * [recompute] must re-check fully (their own text is unchanged but their resolution context moved).
         */
        class Partial(val recompute: Set<Int>, val fineReuse: Int?) : Plan
    }

    /** The signature [Key] for [d] (materializes its text once); the header excludes a function's block body. */
    fun keyOf(d: KtDeclaration): Key {
        val t = d.text
        val header = (d as? KtNamedFunction)?.bodyBlockExpression
            ?.let { t.substring(0, it.textRange.startOffset - d.textRange.startOffset) } ?: t
        return Key(header, t)
    }

    /** Full dependency [Facts] for [d]. Call only when (re)caching a recomputed declaration (walks its subtree). */
    fun factsOf(d: KtDeclaration): Facts = Facts(keyOf(d), providesOf(d), referencesOf(d))

    /** The names [d] exposes to other declarations: its own name, plus a class/object's member names (a depender
     *  references a member by its short name, so a member-signature edit must reach those dependers). */
    fun providesOf(d: KtDeclaration): Set<String> {
        val out = HashSet<String>(4)
        (d as? KtNamedDeclaration)?.name?.let { out += it }
        if (d is KtClassOrObject) d.declarations.forEach { m -> (m as? KtNamedDeclaration)?.name?.let { out += it } }
        return out
    }

    /** Whether [d] provides an `operator fun` (directly or as a class member). Such a function can be invoked by
     *  SYMBOL (`a + b`, `x[i]`, `f()`) with no name-reference a depender's [references] would capture, so a
     *  signature change to one can't be scoped by name and forces a full recompute. (`infix` is exempt: a named
     *  infix call `a foo b` does carry a `foo` name reference.) */
    fun providesOperator(d: KtDeclaration): Boolean = when (d) {
        is KtNamedFunction -> d.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPERATOR_KEYWORD)
        is KtClassOrObject -> d.declarations.any {
            it is KtNamedFunction && it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OPERATOR_KEYWORD)
        }

        else -> false
    }

    /** Every simple name referenced in [d]'s subtree (over-approximated: includes locals/params, which is safe
     *  since they rarely collide with a provider name and an extra recompute only costs work, never correctness). */
    fun referencesOf(d: KtDeclaration): Set<String> {
        val out = HashSet<String>()
        fun rec(p: com.intellij.psi.PsiElement) {
            if (p is KtNameReferenceExpression) p.getReferencedName().let { out += it }
            var c = p.firstChild
            while (c != null) {
                rec(c); c = c.nextSibling
            }
        }
        rec(d)
        return out
    }

    /** The package + import surface of [ktFile], split so [plan] can scope an import edit to the changed names. */
    fun importsOf(ktFile: KtFile): Imports {
        val names = HashSet<String>()
        val stars = HashSet<String>()
        for (imp in ktFile.importDirectives) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (imp.isAllUnder) stars += fqn else names += (imp.aliasName ?: fqn.substringAfterLast(
                '.'
            ))
        }
        return Imports(ktFile.packageDirective?.text ?: "", names, stars)
    }

    /** The changed span in NEW-text coordinates as `[start, end)`, from a prefix/suffix diff. Empty (start==end)
     *  when the texts are equal. Everything outside the span is byte-identical in both texts. */
    fun changedRange(old: CharSequence, new: CharSequence): IntArray {
        val minLen = minOf(old.length, new.length)
        var start = 0
        while (start < minLen && old[start] == new[start]) start++
        var oldEnd = old.length
        var newEnd = new.length
        while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
            oldEnd--; newEnd--
        }
        return intArrayOf(start, newEnd)
    }

    /**
     * The recompute [Plan] against the previous pass's [prev] facts (index-aligned), or [Plan.Full] when the
     * change can't be scoped soundly. [prevImports]/[prevFileText] are the import surface and full text at cache
     * time; [topDecls]/[curImports]/[curFileText] are the current pass's.
     */
    fun plan(
        prev: List<Facts>?,
        prevImports: Imports?,
        prevFileText: String?,
        topDecls: List<KtDeclaration>,
        curImports: Imports,
        curFileText: String,
    ): Plan {
        // No aligned baseline, or a structural change (declaration added/removed/reordered): recompute fully.
        if (prev == null || prevImports == null || prevFileText == null || prev.size != topDecls.size) return Plan.Full
        // A package change or any star-import change affects resolution globally and can't be scoped by name.
        if (prevImports.packageText != curImports.packageText || prevImports.starPackages != curImports.starPackages) return Plan.Full

        val changed = changedDeclIndices(prev, topDecls, prevFileText, curFileText)
        val importNames =
            curImports.names.symmetricDifferenceWith(prevImports.names) // added/removed specific imports
        if (changed.isEmpty() && importNames.isEmpty()) return Plan.Partial(emptySet(), null)
        // An added/removed import whose simple name is a Kotlin OPERATOR-convention function
        // (getValue/setValue for a `by` delegate, plus/get/set/… for `+`/`a[i]`, componentN for destructuring,
        // iterator/hasNext/next for `for`-in) can change the resolution of a symbol invoked BY CONVENTION —
        // which carries no name reference the per-declaration name-scoping below could match. So it can't be
        // scoped; recompute the whole file. This is the common "auto-import `androidx.compose.runtime.getValue`
        // for `var x by mutableStateOf(0)`" case: without this the stale "no getValue operator" error lingers
        // until the next keystroke (which changes the declaration text and forces its recompute). Rare event
        // (a per-accept/per-import-edit keystroke); a plain class/function import stays name-scoped (below).
        if (importNames.any(::isOperatorConventionName)) return Plan.Full

        // Classify each changed declaration: a body-only change invalidates only itself; a signature change
        // (header or provided-name-set differs, and any class edit, since a class's header is its whole text)
        // invalidates its dependents too.
        val triggerNames = HashSet<String>(importNames)
        var multiOrSignature = importNames.isNotEmpty()
        var bodyOnly: Int? = null
        for (i in changed) {
            val key = keyOf(topDecls[i])
            val provides = providesOf(topDecls[i])
            if (key.header != prev[i].key.header || provides != prev[i].provides) {
                // A symbolic-operator signature change can't be scoped by name (its callers use `+`/`[]`/`()`
                // with no name reference), so its dependents can't be found: recompute everything.
                if (providesOperator(topDecls[i])) return Plan.Full
                triggerNames += prev[i].provides
                triggerNames += provides
                multiOrSignature = true
            } else if (bodyOnly == null && changed.size == 1) {
                bodyOnly = i
            } else {
                multiOrSignature = true
            }
        }

        val recompute = HashSet(changed)
        if (triggerNames.isNotEmpty()) {
            for (i in topDecls.indices) {
                if (i in recompute) continue
                if (prev[i].references.any { it in triggerNames }) recompute += i
            }
        }
        // Intra-function statement reuse applies only in the pure single-body-edit case (no dependents fired):
        // a dependent's own text is unchanged, so its per-statement cache would wrongly reuse stale results.
        val fine =
            if (!multiOrSignature && bodyOnly != null && recompute.size == 1) bodyOnly else null
        return Plan.Partial(recompute, fine)
    }

    /** Indices of declarations whose text changed. Only declarations overlapping the edited span are compared;
     *  the rest are byte-identical (guaranteed by [changedRange]) and so unchanged. Index-aligned with [prev].
     *  A pure deletion collapses the new-text span to a point (start == end); the declaration containing that
     *  point still overlaps it, so it is (correctly) compared and flagged. */
    private fun changedDeclIndices(
        prev: List<Facts>,
        topDecls: List<KtDeclaration>,
        prevText: String,
        curText: String
    ): Set<Int> {
        if (prevText == curText) return emptySet() // no textual change (a caret-only re-run)
        val (start, end) = changedRange(prevText, curText)
        val out = HashSet<Int>()
        for (i in topDecls.indices) {
            val r = topDecls[i].textRange
            if (r.endOffset >= start && r.startOffset <= end && topDecls[i].text != prev[i].key.fullText) out += i
        }
        return out
    }

    private fun Set<String>.symmetricDifferenceWith(other: Set<String>): Set<String> =
        (this - other) + (other - this)

    /** The fixed set of Kotlin operator-convention function names — the functions a call site can reach BY
     *  CONVENTION (a symbol/keyword) rather than by a name reference: arithmetic/comparison/range/`in`/indexed-
     *  access/invoke/augmented-assign/increment-decrement/unary operators, plus `by`-delegate accessors and
     *  `provideDelegate`, and the iterator protocol. `componentN` (destructuring) is matched separately (it has a
     *  numeric suffix). Importing an extension with one of these names can bring a convention-invoked operator
     *  into scope, so an import of such a name can't be name-scoped by [plan]. */
    private val OPERATOR_CONVENTION_NAMES = setOf(
        "plus", "minus", "times", "div", "rem", "mod", "rangeTo", "rangeUntil", "contains",
        "get", "set", "invoke", "compareTo", "equals",
        "plusAssign", "minusAssign", "timesAssign", "divAssign", "remAssign", "modAssign",
        "inc", "dec", "unaryPlus", "unaryMinus", "not",
        "iterator", "hasNext", "next", "getValue", "setValue", "provideDelegate",
    )

    /** Whether [name] is a Kotlin operator-convention function name (incl. any `componentN`). */
    private fun isOperatorConventionName(name: String): Boolean =
        name in OPERATOR_CONVENTION_NAMES ||
            (name.startsWith("component") && name.length > 9 && name.substring(9).all { it.isDigit() })
}
