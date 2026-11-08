package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.lang.kotlin.InheritorMarker
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.NavKind
import dev.ide.lang.kotlin.NavTarget
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.KotlinUsageTarget
import dev.ide.lang.dom.TextRange
import kotlin.io.path.readText
import dev.ide.lang.kotlin.interp.PreviewInfo
import dev.ide.vfs.local.fileFor
import java.nio.file.Path
import java.nio.file.Paths

/**
 * WORKSPACE-scoped engine service: the Kotlin-analyzer-backed editor queries carved out of
 * [dev.ide.core.IdeServices] — `@Preview` discovery (the chips the preview surface lists), gutter inheritor
 * markers, and go-to-implementation. Thin delegations to the module's cached [KotlinSourceAnalyzer] over the
 * live buffer. The heavier Compose preview RUN (lower + interpret) stays on the engine for now.
 */
internal class KotlinEditorService(private val ctx: EngineContext) {

    // The module's Kotlin analyzer parses whatever it is handed, so a Java file read as Kotlin would resolve
    // `ArrayList` to `kotlin.collections.ArrayList`.
    private fun isKotlin(file: Path): Boolean = ctx.languageFor(file) == KotlinLanguageBackend.LANGUAGE_ID

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

    /** Source go-to targets ([kind]: declaration / implementation / type / super) for the symbol at [offset] in
     *  [file]'s live buffer. Empty for non-Kotlin files; every target is project source. */
    fun navigationTargets(file: Path, text: String, offset: Int, kind: NavKind): List<NavTarget> {
        if (!isKotlin(file)) return emptyList()
        val analyzer = kotlinAnalyzer(file) ?: return emptyList()
        return analyzer.navigationTargets(ctx.store.vfs.fileFor(file), text, offset, kind)
    }

    /**
     * What Find Usages / Rename at [offset] in [file]'s live buffer acts on, or null (not Kotlin, nothing there).
     * [forRename] takes the symbol's overloads along (see `KotlinEditorFeatures.targetFor`).
     */
    fun usageTarget(file: Path, text: String, offset: Int, forRename: Boolean = false): KotlinUsageTarget? {
        if (!isKotlin(file)) return null
        val analyzer = kotlinAnalyzer(file) ?: return null
        return analyzer.usageTargetAt(ctx.store.vfs.fileFor(file), text, offset, { p -> textOf(Paths.get(p)) }, forRename)
    }

    /**
     * A target for a symbol declared in Java source at [path]:[offset] (its name), so the Kotlin files that use it
     * can be found and renamed with it. [importFqn] is the name a Kotlin import spells it by, when importable.
     */
    fun javaDeclarationTarget(contextFile: Path, path: Path, offset: Int, name: String, importFqn: String?): KotlinUsageTarget? {
        val analyzer = kotlinAnalyzer(contextFile) ?: anyKotlinAnalyzer() ?: return null
        return analyzer.usageTargetForDeclaration(ctx.store.vfs.fileFor(path).path, offset, name, importFqn)
    }

    /**
     * Every reference to [target] across the project's Kotlin files (or only its own file, for a local or
     * private one), as (file, name range), open buffers read over the disk. Each file is resolved by its own
     * module's analyzer.
     */
    fun usages(target: KotlinUsageTarget): List<Pair<Path, TextRange>> {
        val files = target.scope?.let { listOf(Paths.get(it.first)) } ?: ctx.projectKotlinFiles()
        // Everything that can create or look up shared engine state happens here, on the calling thread: the
        // module's analyzer (built lazily, with its module container), the file handle, and the text. Only the
        // per-file parse and resolution below may run on other threads.
        class Job(val path: Path, val analyzer: KotlinSourceAnalyzer, val file: dev.ide.vfs.VirtualFile, val text: String)
        val jobs = files.mapNotNull { f ->
            val abs = f.toAbsolutePath().normalize()
            val text = textOf(abs) ?: return@mapNotNull null
            if (!target.name.let { n -> text.contains(n) }) return@mapNotNull null
            val analyzer = kotlinAnalyzer(abs) ?: return@mapNotNull null
            Job(abs, analyzer, ctx.store.vfs.fileFor(abs), text)
        }
        fun run(job: Job): List<TextRange> = job.analyzer.usagesIn(job.file, job.text, target)
        val results = arrayOfNulls<List<TextRange>>(jobs.size)
        if (jobs.size < PARALLEL_SWEEP_MIN_FILES || SWEEP_THREADS < 2) {
            jobs.forEachIndexed { i, job -> results[i] = run(job) }
        } else {
            // Each file is resolved on its own (its own resolver over the shared, concurrency-safe symbol
            // service), so the files can be spread over the cores: on a phone this sweep is most of a rename.
            val futures = jobs.map { job -> sweepPool.submit<List<TextRange>> { run(job) } }
            futures.forEachIndexed { i, fut ->
                // A file whose worker failed is done again here, so a problem costs time, never a reference.
                results[i] = runCatching { fut.get() }.getOrNull() ?: run(jobs[i])
            }
        }
        val out = ArrayList<Pair<Path, TextRange>>()
        jobs.forEachIndexed { i, job -> results[i]?.forEach { out += job.path to it } }
        return out
    }

    private companion object {
        /** Below this many candidate files the hand-off to other threads costs more than it saves. */
        const val PARALLEL_SWEEP_MIN_FILES = 8

        /** Workers for the usage sweep: the cores less one for the UI, at most four (memory on a phone). */
        val SWEEP_THREADS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

        val sweepPool: java.util.concurrent.ExecutorService by lazy {
            dev.ide.core.BackgroundThreads.pool("kotlin-usages", SWEEP_THREADS)
        }
    }

    /** Highlight-usages-at-caret over [file]'s live buffer; null when not Kotlin or nothing resolves there. */
    fun usageHighlights(file: Path, text: String, offset: Int): List<dev.ide.lang.highlight.UsageHighlight>? {
        if (!isKotlin(file)) return null
        val analyzer = kotlinAnalyzer(file) ?: return null
        return analyzer.usageHighlightsAt(ctx.store.vfs.fileFor(file), text, offset, { p -> textOf(Paths.get(p)) })
    }

    private fun anyKotlinAnalyzer(): KotlinSourceAnalyzer? =
        ctx.projectKotlinFiles().firstOrNull()?.let { kotlinAnalyzer(it.toAbsolutePath().normalize()) }

    private fun textOf(path: Path): String? = ctx.overlayText(path) ?: runCatching { path.readText() }.getOrNull()

    /** The navigation actions applicable at [offset] (each with its resolved source targets). Empty for
     *  non-Kotlin files. */
    fun navigationOptions(file: Path, text: String, offset: Int): List<Pair<NavKind, List<NavTarget>>> {
        if (!isKotlin(file)) return emptyList()
        val analyzer = kotlinAnalyzer(file) ?: return emptyList()
        return analyzer.navigationOptions(ctx.store.vfs.fileFor(file), text, offset)
    }
}
