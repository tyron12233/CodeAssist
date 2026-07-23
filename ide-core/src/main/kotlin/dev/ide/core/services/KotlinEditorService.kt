package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.lang.kotlin.InheritorMarker
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.interp.PreviewInfo
import java.nio.file.Path
import java.nio.file.Paths

/**
 * WORKSPACE-scoped engine service: the Kotlin-analyzer-backed editor queries carved out of
 * [dev.ide.core.IdeServices] — `@Preview` discovery (the chips the preview surface lists), gutter inheritor
 * markers, and go-to-implementation. Thin delegations to the module's cached [KotlinSourceAnalyzer] over the
 * live buffer. The heavier Compose preview RUN (lower + interpret) stays on the engine for now.
 */
internal class KotlinEditorService(private val ctx: EngineContext) {

    private fun kotlinAnalyzer(file: Path): KotlinSourceAnalyzer? {
        val module = ctx.moduleForEditableFile(file) ?: return null
        return ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
    }

    /** The `@Preview` composables declared in [file]'s live buffer. */
    fun composePreviews(file: Path, text: String): List<PreviewInfo> {
        val analyzer = kotlinAnalyzer(file) ?: return emptyList()
        ctx.refreshParse(analyzer, file, text)
        return analyzer.composePreviews(ctx.store.vfs.fileFor(file))
    }

    /** Gutter inheritor ("implementations / is subclassed") markers for [file]'s live buffer — one per
     *  inheritable Kotlin type that has direct subtypes in the index. */
    fun inheritorMarkers(file: Path, text: String): List<InheritorMarker> {
        val analyzer = kotlinAnalyzer(file) ?: return emptyList()
        ctx.refreshParse(analyzer, file, text)
        return analyzer.inheritorMarkers(ctx.store.vfs.fileFor(file))
    }

    /** Resolve an inheritor type [fqn] to its source location for go-to-implementation, bound to
     *  [contextFile]'s module. Null when [fqn] is classpath-only (no navigable project source). */
    fun implementationLocation(contextFile: Path, fqn: String): Pair<Path, Int>? {
        val analyzer = kotlinAnalyzer(contextFile) ?: return null
        return analyzer.declarationLocation(fqn)?.let { (vf, off) -> Paths.get(vf.path) to off }
    }
}
