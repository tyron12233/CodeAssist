package dev.ide.core

import dev.ide.lang.CacheInvalidation
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.platform.Disposable
import java.util.concurrent.ConcurrentHashMap

/**
 * A module's [SourceAnalyzer]s, one per language, built on first use and cached for the module's lifetime.
 *
 * This is the module-scoped service the engine resolves an analyzer through. It exists so that *which*
 * languages a module can analyze is decided by the registered [dev.ide.lang.LanguageBackend]s and nothing
 * else: previously the host held one hand-declared `ServiceKey` per language plus a `when` mapping a
 * [LanguageId] to it, with an `else` branch onto Java, so a backend registered on
 * [dev.ide.lang.LANGUAGE_BACKEND_EP] for a fourth language was selected by `backendFor` and then never
 * reached — the module resolved the Java analyzer for its files instead. Keying by language id inside one
 * service removes that ceiling: registering the backend is now sufficient.
 *
 * Lifetime matches the old per-language services exactly. The module container builds this once, and
 * disposing it disposes every analyzer built through it, in reverse construction order (an analyzer holding
 * a live compiler environment is [Disposable]; one that is not is simply dropped).
 *
 * Thread-safety: analyzers are resolved from the engine dispatcher but also from background index and build
 * work, so the map is concurrent and construction is guarded per language. [build] may be slow (it can
 * construct a compiler environment), so it deliberately runs outside a global lock: two threads racing on
 * *different* languages proceed in parallel.
 */
internal class ModuleAnalyzers(private val build: (LanguageId) -> SourceAnalyzer) : Disposable {

    private val byLanguage = ConcurrentHashMap<String, SourceAnalyzer>()

    /** Construction order, so disposal is LIFO like a service container's. */
    private val order = ArrayList<String>()
    private val orderLock = Any()

    @Volatile
    private var disposed = false

    /** The analyzer for [language], building it on first use. */
    fun analyzer(language: LanguageId): SourceAnalyzer {
        byLanguage[language.id]?.let { return it }
        // computeIfAbsent would hold the bin lock across `build`, which resolves other services and can
        // re-enter this map for another language. Build outside, then publish, discarding a loser's instance.
        val built = build(language)
        val existing = byLanguage.putIfAbsent(language.id, built)
        if (existing != null) {
            (built as? Disposable)?.dispose()
            return existing
        }
        synchronized(orderLock) { order.add(language.id) }
        // Lost the race with dispose(): tear this one down rather than leaking a live compiler environment.
        if (disposed) {
            byLanguage.remove(language.id)
            (built as? Disposable)?.dispose()
        }
        return built
    }

    /** The analyzer already built for [language], or null. Never constructs one. */
    fun peek(language: LanguageId): SourceAnalyzer? = byLanguage[language.id]

    /** Every analyzer built so far. Never constructs one. */
    fun live(): Collection<SourceAnalyzer> = byLanguage.values.toList()

    /**
     * Ask every already-built analyzer to drop the caches [reason] invalidates. Analyzers that were never
     * built have nothing to drop, and building one here would be wrong: it would construct a compiler
     * environment for a language the user has not opened a file in.
     */
    fun invalidateCaches(reason: CacheInvalidation) {
        for (a in live()) runCatching { a.invalidateCaches(reason) }
    }

    override fun dispose() {
        disposed = true
        val ids = synchronized(orderLock) { order.toList().also { order.clear() } }
        for (id in ids.asReversed()) {
            (byLanguage.remove(id) as? Disposable)?.let { runCatching { it.dispose() } }
        }
        byLanguage.clear()
    }
}
