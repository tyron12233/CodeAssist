package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.core.LoweredComposePreview
import dev.ide.core.LoweredPreviewParameter
import dev.ide.core.PreviewRunResult
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import java.nio.file.Path

/**
 * WORKSPACE-scoped engine service: the on-device Compose `@Preview` interpreter path, carved out of
 * [dev.ide.core.IdeServices] — lower a `@Preview` composable (cross-file + cross-module) to a renderable tree,
 * report why it isn't interpretable, run it (through the injected renderer, or report interpretability), and
 * check readiness. Delegations to the module's cached [KotlinSourceAnalyzer] + the Kotlin interpreter; the
 * `@Preview` discovery lives in [KotlinEditorService] and the layout render stays on the engine.
 */
internal class ComposePreviewService(private val ctx: EngineContext) {

    /**
     * The cross-MODULE-expanded preview model for [vf] (already parsed in [entry]'s analyzer): seed from the
     * entry file, then run the reachable-declaration expansion across [module] PLUS its transitive dependency
     * MODULES — so a `data class`/helper declared in a dependency module is lowered + merged and the interpreter
     * can construct/call it (not just same-module siblings).
     *
     * Resolution is OWNERSHIP-routed: a module's source model already spans its dependency modules' sources, so
     * several modules' analyzers can SEE the same dependency file. We LOCATE a reached declaration via any module
     * that can see it (the entry module first), then LOWER it with the analyzer of the module that actually OWNS
     * the file (its own source root contains it) — so a dependency file resolves against ITS module's full
     * classpath, not the entry module's narrower view.
     */
    private fun previewModelFor(
        module: Module, vf: VirtualFile, entry: KotlinSourceAnalyzer,
    ): dev.ide.lang.kotlin.interp.PreviewModel? {
        val kotlinModules = ctx.moduleBuildClosure(module).mapNotNull { m ->
            (ctx.analyzerFor(m, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer)?.let {
                Triple(m, it, ctx.sourceRoots(m).map { r -> r.normalize() })
            }
        }

        fun lowerByOwner(pf: dev.ide.lang.kotlin.symbols.KotlinSymbolService.PreviewSourceFile): dev.ide.lang.kotlin.interp.PreviewFileModel? {
            val path = runCatching { java.nio.file.Paths.get(pf.file.path).normalize() }.getOrNull()
            val owner = path?.let { p ->
                kotlinModules.firstOrNull { (_, _, roots) -> roots.any { p.startsWith(it) } }?.second
            }
            return (owner ?: entry).loweredFile(pf)
        }

        val provider = object : dev.ide.lang.kotlin.interp.PreviewDeclProvider {
            override fun fileDeclaringType(fqn: String): dev.ide.lang.kotlin.interp.PreviewFileModel? =
                kotlinModules.firstNotNullOfOrNull { (_, a, _) -> a.findDeclaringTypeFile(fqn) }
                    ?.let(::lowerByOwner)

            override fun filesDeclaringFunction(name: String): List<dev.ide.lang.kotlin.interp.PreviewFileModel> =
                kotlinModules.flatMap { (_, a, _) -> a.findDeclaringFunctionFiles(name) }
                    .distinctBy { it.file.path }.mapNotNull(::lowerByOwner)
        }
        return entry.lowerFileWithDeps(vf, provider)
    }

    /**
     * Run the `@Preview` composable [functionName] in [file] (buffer [text]): lower the file, verify the
     * preview is fully interpretable, then render it through the injected renderer (on device) — or, with no
     * renderer wired, report that it is interpretable.
     */
    suspend fun runComposePreview(file: Path, text: String, functionName: String): PreviewRunResult {
        val module = ctx.moduleForEditableFile(file) ?: return PreviewRunResult(false, "No module for this file")
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return PreviewRunResult(false, "Not a Kotlin file")
        val vf = ctx.store.vfs.fileFor(file)
        ctx.refreshParse(analyzer, file, text)
        if (analyzer.hasSyntaxErrors(vf)) return PreviewRunResult(
            false, "the file has syntax errors — fix them to preview"
        )
        val model = previewModelFor(module, vf, analyzer)
        val program = model?.program ?: emptyMap()
        val entry = previewEntry(program, functionName, 0)
            ?: return PreviewRunResult(false, "`$functionName` not found as a @Composable")
        if (!entry.isComplete) {
            val why = entry.diagnostics.joinToString("; ") { it.reason }.ifBlank { "unsupported constructs" }
            return PreviewRunResult(false, "Cannot preview `$functionName`: $why")
        }
        ctx.composePreviewRunner?.let { return it.render(entry, program) }
        return PreviewRunResult(true, "`$functionName` is interpretable — on-device rendering coming soon")
    }

    /**
     * Whether [file]'s module can resolve library composables yet. False while the workspace index is still
     * building on first launch or while the hidden Learn Compose scratch's `androidx.compose.*` AARs are still
     * attaching. The preview host polls this so a first-run failure shows a transient "Preparing" state.
     */
    fun composePreviewReady(file: Path): Boolean {
        val module = ctx.moduleForEditableFile(file) ?: return true
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return true
        if (!analyzer.classpathReady()) return false
        val isScratch = ctx.store.rootPath.toString().replace('\\', '/').contains("/.scratch/")
        return !isScratch || analyzer.composeRuntimeAttached()
    }

    /** Why [functionName] in [file] (buffer [text]) isn't interpretable yet: each lowering diagnostic as
     *  `"reason: \"offending source\""`. Empty when it's fully interpretable (or not found). */
    fun composePreviewDiagnostics(
        file: Path, text: String, functionName: String, arity: Int = 0
    ): List<String> = try {
        val module = ctx.moduleForEditableFile(file) ?: return listOf("no module owns this file")
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return listOf("not a Kotlin file")
        val vf = ctx.store.vfs.fileFor(file)
        ctx.refreshParse(analyzer, file, text)
        if (analyzer.hasSyntaxErrors(vf)) return listOf("the file has syntax errors — fix them to preview")
        val model = previewModelFor(module, vf, analyzer)
        val program = model?.program ?: emptyMap()
        val entry = previewEntry(program, functionName, arity)
            ?: return listOf("`$functionName` not found as a @Composable (lowered: ${program.keys.joinToString()})")
        // Report BOTH the entry's own diagnostics AND any reachable source class that fails to lower, so the
        // reason is never invisible. The entry's diagnostics slice from this file's text; a reachable class's
        // offsets are into ANOTHER file, so those are reported by class name + reason without a snippet.
        val entryProblems = entry.diagnostics.map { d ->
            val snippet = text.substring(
                d.source.start.coerceIn(0, text.length), d.source.end.coerceIn(0, text.length)
            ).replace('\n', ' ').trim()
            if (snippet.isBlank()) d.reason else "${d.reason}: \"$snippet\""
        }
        val classes = model?.classes ?: emptyList()
        val reachable = dev.ide.lang.kotlin.interp.reachableSourceClasses(entry, program, classes)
        val classProblems = classes.filter { it.fqn in reachable && !it.isComplete }.flatMap { c ->
            (c.diagnostics + c.methods.values.flatMap { it.diagnostics }).map { "in ${c.simpleName}: ${it.reason}" }
        }
        (entryProblems + classProblems)
            .ifEmpty { listOf("`$functionName` lowered with no diagnostics — it may render; if not, the failure is in the render path") }
    } catch (t: Throwable) {
        listOf("analysis failed: ${t::class.java.simpleName}: ${t.message ?: "no message"}")
    }

    /** Lower the `@Preview` composable [functionName] in [file] (buffer [text]) to a renderable tree + the
     *  file's program (for its source calls), or null when not found / not fully interpretable. */
    fun lowerComposePreview(
        file: Path, text: String, functionName: String, arity: Int = 0
    ): LoweredComposePreview? = dev.ide.lang.kotlin.KotlinPerf.trace("kt.lowerPreview") {
        val module = ctx.moduleForEditableFile(file) ?: return null
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return null
        val vf = ctx.store.vfs.fileFor(file)
        dev.ide.lang.kotlin.KotlinPerf.span("parse") { ctx.refreshParse(analyzer, file, text) }
        // A file with syntax errors mis-shapes declarations when parsed error-tolerantly — interpreting it builds
        // a garbage program that crashes the real Compose runtime in a phase nothing can catch. Don't render it.
        if (analyzer.hasSyntaxErrors(vf)) return null
        val model = previewModelFor(module, vf, analyzer) ?: return null
        val program = model.program
        val entry = previewEntry(program, functionName, arity)?.takeIf { it.isComplete } ?: return null
        // Every source type the preview can actually REACH must lower cleanly too. Scope the check to reachable
        // types only — an unrelated class in the same file is never instantiated by the preview.
        val classes = model.classes
        val reachable = dev.ide.lang.kotlin.interp.reachableSourceClasses(entry, program, classes)
        if (classes.any { it.fqn in reachable && !it.isComplete }) return null
        val parameter = resolvePreviewParameter(analyzer, vf, functionName, arity, classes)
        LoweredComposePreview(entry, program, classes, parameter)
    }

    /** The lowered preview function for [functionName] at [arity] (a `@PreviewParameter` preview has arity > 0);
     *  falls back to any arity of that name so a stale arity still resolves. */
    private fun previewEntry(
        program: Map<String, ResolvedFunction>, functionName: String, arity: Int,
    ): ResolvedFunction? = program["$functionName/$arity"] ?: program["$functionName/0"]
    ?: program.entries.firstOrNull { it.key.substringBeforeLast('/') == functionName }?.value

    /** Resolve the `@PreviewParameter` provider for [functionName]/[arity] (if any) to something the renderer
     *  can instantiate: the lowered source [ResolvedClass] when it's project source, else a best-effort FQN. */
    private fun resolvePreviewParameter(
        analyzer: KotlinSourceAnalyzer,
        vf: VirtualFile, functionName: String, arity: Int,
        classes: List<ResolvedClass>,
    ): LoweredPreviewParameter? {
        val info = analyzer.composePreviews(vf)
            .firstOrNull { it.functionName == functionName && it.arity == arity }?.parameter
            ?: return null
        val source =
            classes.firstOrNull { it.simpleName == info.providerName || it.fqn == info.providerName }
        return LoweredPreviewParameter(
            providerSimpleName = info.providerName,
            providerFqn = source?.fqn ?: analyzer.previewProviderFqn(vf, info.providerName),
            providerClass = source,
            limit = info.limit,
        )
    }
}
