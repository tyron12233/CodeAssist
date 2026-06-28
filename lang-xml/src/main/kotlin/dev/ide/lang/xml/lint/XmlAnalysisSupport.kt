package dev.ide.lang.xml.lint

import dev.ide.analysis.DIAGNOSTIC_PROVIDER_EP
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Registration entry point for the XML editor diagnostics: contributes the [XmlDiagnosticProvider] on
 * `platform.diagnosticProvider`, wired to the host's resource lookups ([XmlResourceHost]) and Android attribute
 * schema ([XmlAttributeChecker]). The detection rules ([XmlLintRules]), the provider, and the quick-fixes
 * ([XmlQuickFixes]) are each their own unit; this only wires them into the platform.
 */
object XmlAnalysisSupport {
    val PLUGIN = PluginId("xml-analysis")

    fun register(
        extensions: ExtensionRegistry,
        host: XmlResourceHost,
        attributes: XmlAttributeChecker = XmlAttributeChecker.NONE,
        plugin: PluginId = PLUGIN,
    ) {
        extensions.register(DIAGNOSTIC_PROVIDER_EP, XmlDiagnosticProvider(host, attributes), plugin)
    }
}
