package dev.ide.lang.jdt.analysis

import dev.ide.analysis.ACTION_PROVIDER_EP
import dev.ide.analysis.ANALYZER_EP
import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.analysis.QUICK_FIX_PROVIDER_EP
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Contributes the Java (JDT) editor analysis surface — the syntax analyzers, the compiler diagnostic
 * provider, and the quick-fix / intention providers — onto the analysis-api extension points. Mirrors
 * `AndroidSupport.register`: the host (ide-core) calls this once per engine, so adding the Java editor
 * features is a registration rather than host code. Everything here declares `languages = {java}`, so
 * once every language flows through the one analysis pipeline these never run on Kotlin/XML files.
 */
object JdtAnalysisSupport {
    val PLUGIN = PluginId("jdt-analysis")

    fun register(extensions: ExtensionRegistry, plugin: PluginId = PLUGIN) {
        extensions.register(ANALYZER_EP, SystemOutCallAnalyzer(), plugin)
        extensions.register(ANALYZER_EP, UnusedImportAnalyzer(), plugin)
        extensions.register(DIAGNOSTIC_PROVIDER_EP, CompilerDiagnosticProvider(), plugin)
        extensions.register(QUICK_FIX_PROVIDER_EP, AddImportQuickFixProvider(), plugin)
        extensions.register(QUICK_FIX_PROVIDER_EP, RemoveUnusedImportQuickFixProvider(), plugin)
        extensions.register(ACTION_PROVIDER_EP, IntroduceVariableActionProvider(), plugin)
        extensions.register(ACTION_PROVIDER_EP, SurroundWithTryCatchActionProvider(), plugin)
    }
}
