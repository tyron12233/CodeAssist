package dev.codeassist.csharp.editor

import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.completion.CsSemanticCompletion
import dev.ide.analysis.AnalysisTarget
import dev.ide.analysis.DECLARATION_PROVIDER_EP
import dev.ide.analysis.DeclarationProvider
import dev.ide.analysis.NavigationTarget
import dev.ide.lang.LanguageId
import dev.ide.plugin.PluginRegistration

/**
 * Go to Declaration for C#: Roslyn resolves the name at the caret and says where the project declares it.
 * A symbol from the framework or a package has no source here and gives no target.
 */
internal class CsDeclarationProvider(
    private val semantic: CsSemanticCompletion,
) : DeclarationProvider {

    override val id = "csharp.declaration"
    override val languages = setOf(LanguageId(CSharpPlugin.LANGUAGE))

    override suspend fun declarations(target: AnalysisTarget, offset: Int): List<NavigationTarget> {
        CsModules.remember(target.module)
        val found = semantic.definitionsAt(target.file.path, target.parsed.text().toString(), offset)
            ?: return emptyList()
        return found.map { NavigationTarget(it.path, it.offset, it.label) }
    }
}

/**
 * Registers [CsDeclarationProvider]. A class of its own so that a host without the extension point
 * (before SPI 3.1.0) fails to link here, where the caller guards it, and not in the plugin's entry point.
 */
internal object CsNavigationRegistration {
    fun register(reg: PluginRegistration, semantic: CsSemanticCompletion) {
        reg.register(DECLARATION_PROVIDER_EP, CsDeclarationProvider(semantic))
    }
}
