package dev.ide.analysis

import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.platform.ServiceKey

/**
 * A module's source analyzers, one per language: the slice of the engine's analyzer service a plugin can
 * name. MODULE-scoped, so it is resolved from the `Module` an extension-point callback already hands you.
 *
 * An analyzer is how a plugin reaches resolution and diagnostics for code it did not parse itself. The
 * engine's own service additionally builds, caches, invalidates and disposes them, and which languages it
 * can serve is decided by the registered `LanguageBackend`s rather than by this interface. A consumer has
 * no business driving any of that, so only the two reads are here.
 */
interface ModuleAnalysis {

    /**
     * The analyzer for [language], building it on first use. A first build can be slow, since it may
     * construct a whole compiler environment, so keep it off a latency-sensitive path.
     *
     * A [language] that no registered backend claims does not fail here: the engine falls back to its
     * first registered backend, which then analyzes the file as some other language entirely. Ask only for
     * a language you know has a backend, which for a plugin means one it registered itself.
     */
    fun analyzer(language: LanguageId): SourceAnalyzer

    /** The analyzer already built for [language], or null. Never constructs one. */
    fun peek(language: LanguageId): SourceAnalyzer?
}

/** MODULE-scoped [ModuleAnalysis] for one module. */
val MODULE_ANALYSIS = ServiceKey<ModuleAnalysis>("platform.moduleAnalysis")
