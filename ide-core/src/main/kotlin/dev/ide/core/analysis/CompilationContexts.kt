package dev.ide.core.analysis

import dev.ide.lang.CompilationContext
import dev.ide.lang.CompilationContextProvider
import dev.ide.lang.LanguageId
import dev.ide.model.Module
import dev.ide.model.Workspace

/**
 * Picks the [CompilationContext] a language backend is handed: a plugin's [CompilationContextProvider] if one
 * claims the language and answers, otherwise the host's model-derived context.
 *
 * Its own object so the selection rules are testable without an engine, and because they are the contract a
 * plugin author is told about on [CompilationContextProvider]:
 *
 *  - only providers claiming [language] are asked, in registration order;
 *  - the first non-null answer wins, and a null means "not mine after all";
 *  - a provider that throws is reported through [onError] and skipped, never propagated. Analysis of every
 *    other language in the project must not stop because one plugin's provider is broken.
 */
internal object CompilationContexts {

    fun resolve(
        providers: List<CompilationContextProvider>,
        workspace: Workspace,
        module: Module,
        language: LanguageId,
        variant: Set<String>?,
        onError: (CompilationContextProvider, Throwable) -> Unit = { _, _ -> },
        fallback: () -> CompilationContext,
    ): CompilationContext {
        for (provider in providers) {
            if (language !in provider.languages) continue
            val contributed = try {
                provider.contextFor(workspace, module, language, variant)
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                onError(provider, t)
                null
            }
            if (contributed != null) return contributed
        }
        return fallback()
    }
}
