package dev.ide.lang.kotlin.analysis

import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.symbols.KotlinSymbolService

/**
 * The read-only analysis context a Kotlin diagnostic checker runs against, the counterpart to the K2 Analysis
 * API's `KaSession`: the [resolver] over the current parse plus the ambient facts a check needs. It exposes
 * only queries, so a checker cannot mutate the PSI, the symbol model, or the resolver's state through it. One
 * session is built per analyze pass and shared by every checker in that pass.
 */
class KotlinAnalysisSession(
    val resolver: KotlinResolver,
    val service: KotlinSymbolService,
    /** Same-file typealias names (the disk model may lag the buffer being edited). */
    val localAliases: Set<String>,
    /** The classpath index is built, so the classpath-dependent checks (unresolved symbol / type) can run
     *  without false positives. False during "dumb mode": those checks back off until the index is ready. */
    val resolveReady: Boolean,
)

/**
 * The collect-only sink a checker emits through, the counterpart to K2's `DiagnosticReporter`. A checker can
 * report but read nothing back, so a pass cannot depend on what earlier checkers found.
 */
class DiagnosticReporter {
    private val collected = ArrayList<Diagnostic>()

    fun report(diagnostic: Diagnostic?) { if (diagnostic != null) collected += diagnostic }

    fun reportAll(diagnostics: List<Diagnostic>) { collected += diagnostics }

    /** The diagnostics reported so far, in report order. */
    fun drain(): List<Diagnostic> = collected
}

/**
 * What each [KotlinChecker] receives for one element: the read-only [session], the [reporter] to emit through,
 * and [skipCrossStatement]. The incremental path walks a function's top-level statements separately, so the
 * whole-body cross-statement checks (unused local / var-could-be-val, which read sibling statements) are run
 * once over the whole function rather than per statement; [skipCrossStatement] tells a checker to leave them
 * to that pass. The [resolver]/[service]/etc. accessors forward the session so a checker body reads them
 * directly (`resolver.inferType(x)`), mirroring `with(session)` in the K2 API.
 */
class CheckerContext(
    val session: KotlinAnalysisSession,
    val reporter: DiagnosticReporter,
    val skipCrossStatement: Boolean,
) {
    val resolver get() = session.resolver
    val service get() = session.service
    val localAliases get() = session.localAliases
    val resolveReady get() = session.resolveReady

    fun report(diagnostic: Diagnostic?) = reporter.report(diagnostic)
    fun reportAll(diagnostics: List<Diagnostic>) = reporter.reportAll(diagnostics)
}
