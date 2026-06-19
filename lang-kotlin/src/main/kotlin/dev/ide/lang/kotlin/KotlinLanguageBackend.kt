package dev.ide.lang.kotlin

import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.SourceCompiler
import dev.ide.lang.kotlin.compile.KotlinSourceCompiler

/**
 * The Kotlin [LanguageBackend], providing editor-time code intelligence.
 *
 * It parses with the Kotlin PSI parser but discards the compiler's resolution/FIR, building its own
 * symbol table, inference, and completion on the neutral DOM.
 *
 * Capabilities: ERROR_RECOVERY (the tolerant PSI parse), BINDINGS, and COMPLETION are provided by the
 * resolve/completion packages.
 *
 * Host wiring: ide-core registers it on `LANGUAGE_BACKEND_EP`, maps `.kt -> kotlin` in `languageFor`, and
 * injects the index service in `analyzerFor` via `is KotlinSourceAnalyzer -> it.indexService = indexService`,
 * mirroring the JDT/XML backends.
 */
class KotlinLanguageBackend : LanguageBackend {
    override val id: String = "kotlin"
    override val languages: Set<LanguageId> = setOf(LANGUAGE_ID)
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.BINDINGS,
        BackendCapability.COMPLETION,
        BackendCapability.INLAY_HINTS,
        BackendCapability.SEMANTIC_HIGHLIGHT,
        BackendCapability.COMPILE,
    )

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = KotlinSourceAnalyzer(ctx)

    /** K2 codegen to `.class`. The build graph normally drives Kotlin through the lighter
     *  `dev.ide.build.engine.KotlinCompile` port; this is the language-api SPI path. */
    override fun createCompiler(ctx: CompilationContext): SourceCompiler? = KotlinSourceCompiler(ctx)

    companion object {
        val LANGUAGE_ID = LanguageId("kotlin")
    }
}
