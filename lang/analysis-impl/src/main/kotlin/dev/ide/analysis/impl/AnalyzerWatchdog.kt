package dev.ide.analysis.impl

import dev.ide.analysis.Analyzer
import dev.ide.analysis.AnalyzerId
import dev.ide.analysis.AnalyzerTier
import dev.ide.platform.PluginId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How long a third-party analyzer may take before the engine counts the pass against it, and how many
 * consecutive bad passes it takes to drop it.
 *
 * The budgets are deliberately loose next to what the built-ins cost (the two JDT analyzers measure 79µs
 * and 423µs on a 34KB file, against a ~534µs shared DOM walk), because the cost being bounded here is the
 * user's typing latency, not the analyzer's efficiency: everything in a file pass runs on the one
 * serialized engine thread, so an analyzer over budget is holding up the compiler's errors, the other
 * analyzers, and the code completion queued behind them.
 */
data class AnalyzerBudget(
    /** Per-pass budget for a SYNTAX analyzer — the DOM-only tier, which runs closest to every keystroke. */
    val syntaxMs: Long = 25,
    /** Per-pass budget for a SEMANTIC analyzer, which resolves types and legitimately costs more. */
    val semanticMs: Long = 120,
    /** Per-sweep budget for a PROJECT analyzer (batch lint / the coalesced sweep, off the typing path). */
    val projectMs: Long = 2_000,
    /** Consecutive over-budget or failing passes before the analyzer is quarantined. */
    val strikes: Int = 3,
) {
    fun forTier(tier: AnalyzerTier): Long = when (tier) {
        AnalyzerTier.SYNTAX -> syntaxMs
        AnalyzerTier.SEMANTIC -> semanticMs
        AnalyzerTier.PROJECT -> projectMs
    }

    companion object {
        val DEFAULT = AnalyzerBudget()
    }
}

/** A third-party analyzer the watchdog took off the pass, and why. */
data class QuarantinedAnalyzer(
    val analyzer: AnalyzerId,
    val displayName: String,
    val plugin: PluginId,
    val cause: Cause,
    /** The pass that triggered the quarantine, in ms (0 when [cause] is [Cause.FAILING]). */
    val tookMs: Long,
    /** The budget it was held to, in ms. */
    val budgetMs: Long,
    /** How many times it has been dropped this session, this one included: above 1, turning it back on did not
     *  help, which a host can say instead of offering the same choice again. */
    val times: Int = 1,
) {
    enum class Cause {
        /** Ran over its tier's budget on [AnalyzerBudget.strikes] consecutive passes. */
        SLOW,

        /** Threw on [AnalyzerBudget.strikes] consecutive passes. */
        FAILING,
    }
}

/**
 * Holds third-party analyzers to [AnalyzerBudget] and drops the ones that will not keep to it.
 *
 * Only the *next* pass can be protected: an analyzer already running on the engine thread cannot be
 * interrupted (it is plain synchronous code that may never poll `AnalysisTarget.checkCanceled`), so the
 * watchdog measures what a pass cost and decides whether the analyzer runs again. Strikes are
 * consecutive — one slow pass over a pathological file is not a verdict, three in a row is — and any
 * pass inside budget clears the count.
 *
 * Quarantine is a *runtime* state, deliberately not written into the user's [dev.ide.analysis.AnalysisProfile]:
 * the user never turned this check off, the IDE did. It ends when the host [release]s it (the user turning it
 * back on), when the user switches that check off or on themselves, or with the session.
 *
 * Built-in analyzers are never quarantined. A slow built-in is our own bug to fix, and half the IDE's
 * diagnostics would be silently missing if the watchdog could switch the Kotlin or JDT analyzers off.
 */
internal class AnalyzerWatchdog(
    private val budget: AnalyzerBudget,
    private val onQuarantine: (QuarantinedAnalyzer) -> Unit,
) {

    private val strikes = ConcurrentHashMap<AnalyzerId, Int>()

    @Volatile
    private var quarantinedIds: Set<AnalyzerId> = emptySet()

    /** Every analyzer quarantined this session, in the order they were dropped. */
    val quarantined = CopyOnWriteArrayList<QuarantinedAnalyzer>()

    /** Times each analyzer was dropped this session. Survives [release] and [clear] on purpose: it is what tells
     *  a second drop from a first. */
    private val drops = ConcurrentHashMap<AnalyzerId, Int>()

    fun isQuarantined(id: AnalyzerId): Boolean = id in quarantinedIds

    /** Record a completed pass of [analyzer] that took [tookMs]. */
    fun completed(analyzer: Analyzer, plugin: PluginId, tookMs: Long) {
        val budgetMs = budget.forTier(analyzer.tier)
        if (tookMs <= budgetMs) {
            strikes.remove(analyzer.id)
            return
        }
        strike(analyzer, plugin, QuarantinedAnalyzer.Cause.SLOW, tookMs, budgetMs)
    }

    /** Record a pass of [analyzer] that threw. */
    fun failed(analyzer: Analyzer, plugin: PluginId) {
        strike(analyzer, plugin, QuarantinedAnalyzer.Cause.FAILING, tookMs = 0, budgetMs = budget.forTier(analyzer.tier))
    }

    /** Put [id] back on the pass with no strikes against it. False when it was not quarantined. */
    fun release(id: AnalyzerId): Boolean {
        strikes.remove(id)
        if (id !in quarantinedIds) return false
        quarantinedIds = quarantinedIds - id
        quarantined.removeAll { it.analyzer == id }
        return true
    }

    /** Forget every strike and release every quarantine. */
    fun clear() {
        strikes.clear()
        quarantinedIds = emptySet()
        quarantined.clear()
    }

    private fun strike(
        analyzer: Analyzer, plugin: PluginId, cause: QuarantinedAnalyzer.Cause, tookMs: Long, budgetMs: Long,
    ) {
        val count = strikes.merge(analyzer.id, 1, Int::plus) ?: 1
        if (count < budget.strikes || isQuarantined(analyzer.id)) return
        val times = drops.merge(analyzer.id, 1, Int::plus) ?: 1
        val report = QuarantinedAnalyzer(analyzer.id, analyzer.displayName, plugin, cause, tookMs, budgetMs, times)
        quarantinedIds = quarantinedIds + analyzer.id
        quarantined += report
        strikes.remove(analyzer.id)
        onQuarantine(report)
    }
}
