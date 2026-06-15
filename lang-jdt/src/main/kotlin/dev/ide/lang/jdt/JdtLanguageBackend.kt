package dev.ide.lang.jdt

import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.SourceCompiler

/**
 * The default Java [LanguageBackend], engine = Eclipse JDT. Error-tolerant (statement + binding
 * recovery), so editor features work on broken code; bindings provide the access/modifier info that
 * powers smart completion. Implements the backend-neutral language-api SPI.
 */
class JdtLanguageBackend : LanguageBackend {
    override val id: String = "jdt"
    override val languages: Set<LanguageId> = setOf(LanguageId("java"))
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.BINDINGS,
        BackendCapability.COMPLETION,
        BackendCapability.SNIPPETS,
        BackendCapability.POSTFIX,
        BackendCapability.INLAY_HINTS,
        BackendCapability.COMPILE,
    )

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = JdtSourceAnalyzer(ctx)

    override fun createCompiler(ctx: CompilationContext): SourceCompiler = JdtSourceCompiler(ctx)
}
