package dev.ide.core.services

import dev.ide.analysis.Reanalysis
import dev.ide.core.EngineContext
import java.nio.file.Paths

/**
 * The published [Reanalysis] over the engine's own request ([EngineContext.requestReanalysis]). A malformed
 * path a plugin passes is skipped rather than failing the call, since the rest of the request still stands.
 */
internal class ReanalysisService(private val engine: EngineContext) : Reanalysis {

    override fun reanalyze(paths: Collection<String>) {
        val resolved = paths.mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
        if (resolved.isNotEmpty()) engine.requestReanalysis(resolved)
    }

    override fun reanalyzeOpenFiles() = engine.requestReanalysis(null)
}
