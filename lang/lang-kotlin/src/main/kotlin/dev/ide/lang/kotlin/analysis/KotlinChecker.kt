package dev.ide.lang.kotlin.analysis

import com.intellij.psi.PsiElement
import dev.ide.platform.EngineCancellation

/**
 * A diagnostic checker for one PSI element kind, the counterpart to a K2 `Fir*Checker`: given an element that
 * is an instance of [type] and a read-only [CheckerContext], it reports diagnostics through the context's
 * reporter. Checkers are pure and read-only. A checker registered on a supertype (e.g. `KtDeclaration`) runs
 * for every element of that type, matching the pre-dispatch checks the old monolithic walk ran by hand.
 */
class KotlinChecker(
    val type: Class<out PsiElement>,
    val check: CheckerContext.(PsiElement) -> Unit,
)

/**
 * Runs a set of [KotlinChecker]s over a PSI subtree in a single read-only walk, the counterpart to K2's
 * checker runner. For each node it invokes, in registration order, every checker whose [type] the node is an
 * instance of, then descends into the children. Preserves the editor's incremental hooks: [stopAt] prunes the
 * subtrees the caller analyzes separately, and [skipCrossStatement] gates the whole-body local checks.
 */
class KotlinCheckerDriver(private val checkers: List<KotlinChecker>) {

    // The checkers applicable to a concrete PSI class, computed once per distinct class encountered (there are
    // only a few dozen node classes in a file), so the per-node cost is a map lookup plus the short matched list.
    private val byClass = HashMap<Class<*>, List<KotlinChecker>>()

    private fun forClass(cls: Class<*>): List<KotlinChecker> =
        byClass.getOrPut(cls) { checkers.filter { it.type.isAssignableFrom(cls) } }

    fun run(
        root: PsiElement,
        session: KotlinAnalysisSession,
        reporter: DiagnosticReporter,
        stopAt: Set<PsiElement> = emptySet(),
        skipCrossStatement: Boolean = false,
    ) = walk(root, CheckerContext(session, reporter, skipCrossStatement), stopAt)

    private fun walk(psi: PsiElement, ctx: CheckerContext, stopAt: Set<PsiElement>) {
        if (psi in stopAt) return
        // Poll between nodes (never mid-check) so a higher-priority editor call sharing the one engine thread
        // (code completion) can preempt this pass rather than waiting it out; the host retries.
        EngineCancellation.checkCanceled()
        val applicable = forClass(psi.javaClass)
        for (checker in applicable) checker.check(ctx, psi)
        var c = psi.firstChild
        while (c != null) { walk(c, ctx, stopAt); c = c.nextSibling }
    }
}
