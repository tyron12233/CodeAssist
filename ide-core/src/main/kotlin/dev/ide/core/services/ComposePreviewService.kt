package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.core.LoweredComposePreview
import dev.ide.core.settings.BuiltInSettingsPages
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

    private companion object {
        const val INDEXING_MESSAGE = "Preparing preview — indexing dependencies…"
    }

    /** The cross-module lowered model for [vf], shared with the plugin-facing interpreter (see
     *  [loweredModelFor], which carries the ownership-routing rationale). */
    private fun previewModelFor(
        module: Module, vf: VirtualFile, entry: KotlinSourceAnalyzer,
    ): dev.ide.lang.kotlin.interp.PreviewModel? = loweredModelFor(ctx, module, vf, entry)

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
        unlowerableReason(analyzer, vf)?.let { return PreviewRunResult(false, it) }
        val model = previewModelFor(module, vf, analyzer)
        val program = model?.program ?: emptyMap()
        val entry = previewEntry(program, functionName, 0)
            ?: return PreviewRunResult(false, "`$functionName` not found as a @Composable")
        val refusals = previewRefusals(entry, program, model?.classes ?: emptyList(), text) +
            errorRefusals(analyzer, vf)
        if (refusals.isNotEmpty()) {
            return PreviewRunResult(false, "Cannot preview `$functionName`: ${refusals.joinToString("; ")}")
        }
        ctx.composePreviewRunner?.let { return it.render(entry, program) }
        return PreviewRunResult(true, "`$functionName` is interpretable — on-device rendering coming soon")
    }

    /**
     * Whether [file]'s module can resolve library composables yet. False while the workspace index is still
     * building on first launch or while the hidden Learn Compose scratch's `androidx.compose.*` AARs are still
     * attaching. The preview host polls this so a first-run failure shows a transient "Preparing" state.
     */
    /** The sandbox categories this project's Compose Preview settings restrict — `SandboxCategory.id` strings
     *  (the hosts feed them to `PreviewSandboxPolicy.fromIds`; ide-core itself doesn't link interp-core). The
     *  toggles default ON, so a project with nothing stored restricts everything. */
    fun sandboxCategories(): Set<String> = buildSet {
        fun blocked(key: String) =
            ctx.projectPref("settings.${BuiltInSettingsPages.PREVIEW}.$key")?.toBooleanStrictOrNull() ?: true
        if (blocked(BuiltInSettingsPages.SANDBOX_FILE_IO)) add("fileIo")
        if (blocked(BuiltInSettingsPages.SANDBOX_NETWORK)) add("network")
        if (blocked(BuiltInSettingsPages.SANDBOX_ANDROID)) add("androidSystem")
        if (blocked(BuiltInSettingsPages.SANDBOX_PROCESS)) add("processControl")
    }

    /** Whether the `@Preview` should render in the `:preview` OS process (the isolation toggle, default ON).
     *  The Android host reads this to choose the remote streaming path over the in-process renderer, so a crash or
     *  runaway recomposition pegs only `:preview`, not the IDE. Parameterized / locale previews still fall back
     *  in-process (the remote path doesn't cover them yet), as does any remote failure. */
    fun previewIsolated(): Boolean =
        ctx.projectPref("settings.${BuiltInSettingsPages.PREVIEW}.${BuiltInSettingsPages.PREVIEW_ISOLATE}")
            ?.toBooleanStrictOrNull() ?: true

    fun composePreviewReady(file: Path): Boolean {
        val module = ctx.moduleForEditableFile(file) ?: return true
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return true
        // Ready once the index is no longer BUILDING — not classpathReady(). A finished-but-partial index (a
        // skipped/undecoded jar) never flips `ready`, so gating on it leaves the preview stuck at "Preparing
        // libraries" forever after indexing finishes; the host polls this, so "still building" is the right wait.
        if (analyzer.classpathIndexBuilding()) return false
        val isScratch = ctx.store.rootPath.toString().replace('\\', '/').contains("/.scratch/")
        return !isScratch || analyzer.composeRuntimeAttached()
    }

    /** Why [functionName] in [file] (buffer [text]) isn't interpretable yet: each lowering diagnostic as
     *  `"reason: \"offending source\""`. Empty when it's fully interpretable (or not found). Runs the SAME
     *  gate [lowerComposePreview] renders behind ([previewRefusals]), so a refused preview always has a reason
     *  and a rendered one never reports a problem it didn't act on. */
    fun composePreviewDiagnostics(
        file: Path, text: String, functionName: String, arity: Int = 0
    ): List<String> = try {
        val module = ctx.moduleForEditableFile(file) ?: return listOf("no module owns this file")
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return listOf("not a Kotlin file")
        val vf = ctx.store.vfs.fileFor(file)
        ctx.refreshParse(analyzer, file, text)
        unlowerableReason(analyzer, vf)?.let { return listOf(it) }
        val model = previewModelFor(module, vf, analyzer)
        val program = model?.program ?: emptyMap()
        val entry = previewEntry(program, functionName, arity)
            ?: return listOf("`$functionName` not found as a @Composable (lowered: ${program.keys.joinToString()})")
        // The lowering reasons lead: they name the offending declaration, where an error message only names a
        // symbol. Both are reported, so nothing the gate refused on is invisible.
        (previewRefusals(entry, program, model?.classes ?: emptyList(), text) + errorRefusals(analyzer, vf))
            .ifEmpty { listOf("`$functionName` lowered with no diagnostics — it may render; if not, the failure is in the render path") }
    } catch (e: dev.ide.platform.EngineCanceledException) {
        // Preemption is the scheduler's control flow, not a diagnosis: swallowing it here would report
        // "analysis failed: EngineCanceledException" to the panel AND rob the preview lane of its retry.
        throw e
    } catch (t: Throwable) {
        listOf("analysis failed: ${t::class.java.simpleName}: ${t.message ?: "no message"}")
    }

    /**
     * Why the buffer may not even be LOWERED, or null when it may. Both answers are single, self-explaining
     * messages, so a caller reports this one reason and stops.
     *
     *  - **Syntax errors.** The error-tolerant parser still yields a whole tree, but a stray/incomplete token
     *    mis-shapes declarations — `data class Project(dsad val id: …)` parses into a constructor whose
     *    parameters are all shifted, so the interpreter builds objects with wrong-typed fields. That garbage
     *    program crashes the real Compose runtime deep in its measure/semantics pass, AFTER the interpreter
     *    returned, where no interpreter-level guard can catch it. Lowering such a tree is meaningless, so this
     *    stops even the diagnostics path.
     *  - **Still-indexing classpath.** Every library call lowers to `candidates=0` while the callable index
     *    builds, so the program looks broken for a reason that passes on its own. Gate on "still building",
     *    NOT `classpathReady()` — a finished-but-partial index (a skipped/undecoded jar) never flips `ready`,
     *    and waiting for it wedges the preview at "Preparing" forever; resolution still answers from the open
     *    segments.
     */
    private fun unlowerableReason(analyzer: KotlinSourceAnalyzer, vf: VirtualFile): String? = when {
        analyzer.hasSyntaxErrors(vf) -> "the file has syntax errors — fix them to preview"
        analyzer.classpathIndexBuilding() -> INDEXING_MESSAGE
        else -> null
    }

    /**
     * The buffer's own errors that forbid interpreting it, phrased for the panel; empty means it has none.
     *
     * This is the check the typing race actually needed. Lowering already refuses a call it cannot resolve,
     * but half-typed code very often parses AND lowers *cleanly* and is only wrong by the time the real
     * Compose runtime sees the values — an argument of the wrong type, a name used as a value, an uninferable
     * type variable. Compose then fails in its measure/layout/semantics pass, after the interpreter has
     * returned and outside any guard it owns, which takes the composition (and the IDE) with it.
     *
     * See [KotlinDiagnosticCodes.PREVIEW_BLOCKING] for which errors these are and why it is a named set rather
     * than "any error". Reported as its own reasons rather than short-circuiting, so a preview that ALSO has a
     * more specific lowering problem still names it.
     */
    private fun errorRefusals(analyzer: KotlinSourceAnalyzer, vf: VirtualFile): List<String> =
        analyzer.previewBlockingErrors(vf).map { "the file has errors — fix them to preview: $it" }

    /**
     * Why the lowered [entry] may not be interpreted, phrased for the panel; empty means it is safe to render.
     *
     * The single decision behind both [lowerComposePreview] and [composePreviewDiagnostics] — they used to
     * carry their own copies, which is how a refused preview once reported "lowered with no diagnostics".
     *
     * Nothing the preview can REACH is allowed to be missing or to have lowered imperfectly. Reachability is
     * followed through resolved callees (not names), so this stays scoped to what the render will actually
     * touch: an unrelated broken class or helper elsewhere in the file can never be reached by this preview
     * and must not block it.
     */
    private fun previewRefusals(
        entry: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass>,
        text: String,
    ): List<String> {
        // The entry's own diagnostics slice from THIS buffer's text, so they can quote the offending source.
        val entryProblems = entry.diagnostics.map { d ->
            val snippet = text.substring(
                d.source.start.coerceIn(0, text.length), d.source.end.coerceIn(0, text.length)
            ).replace('\n', ' ').trim()
            if (snippet.isBlank()) d.reason else "${d.reason}: \"$snippet\""
        }
        // A reachable top-level FUNCTION the preview calls (`CounterPreview { Counter() }`, where `Counter`
        // fails to resolve `Column` / a `by remember { mutableStateOf }` delegate). Its spans are into its own
        // (possibly cross-file) text, so report by name + reason rather than snippeting from THIS buffer.
        val fnProblems = dev.ide.lang.kotlin.interp.reachableSourceFunctions(entry, program, classes)
            .filter { it !== entry && !it.isComplete }
            .flatMap { fn -> fn.diagnostics.map { "in ${fn.name}: ${it.reason}" } }
        // A reachable source CLASS that didn't lower cleanly would be constructed with wrong-typed fields.
        val reachable = dev.ide.lang.kotlin.interp.reachableSourceClasses(entry, program, classes)
        val classProblems = classes.filter { it.fqn in reachable && !it.isComplete }.flatMap { c ->
            (c.diagnostics + c.methods.values.flatMap { it.diagnostics }).map { "in ${c.simpleName}: ${it.reason}" }
        }
        // A callee the resolver bound to project source that lowering never supplied: the interpreter throws
        // "no source function" on it mid-composition, with groups already open.
        val missing = dev.ide.lang.kotlin.interp.missingSourceCallees(entry, program, classes)
            .map { "`${it.substringBeforeLast('/')}` is called but wasn't lowered (its declaration is missing or didn't parse)" }
        return entryProblems + fnProblems + classProblems + missing
    }

    /**
     * Lower the `@Preview` composable [functionName] in [file] (buffer [text]) to a renderable tree + the
     * file's program (for its source calls), or null when it is not found or must not be interpreted.
     *
     * Null is the load-bearing answer here, not a fallback: the host keeps its last good render on screen when
     * this returns null, so refusing costs a stale frame, while rendering a program built from half-typed code
     * costs the IDE. The interpreter's `tolerateGaps` is a backstop for a construct WE don't support inside
     * otherwise-valid code — not a licence to interpret the user's broken code, because a gap skipped
     * mid-statement can leave the live composer's groups unbalanced. So the buffer must be lowerable at all
     * ([unlowerableReason]) and free of interpreter-blocking errors ([errorRefusals]), and everything the
     * preview can reach must be present and have lowered cleanly ([previewRefusals]).
     */
    fun lowerComposePreview(
        file: Path, text: String, functionName: String, arity: Int = 0,
    ): LoweredComposePreview? = dev.ide.lang.kotlin.KotlinPerf.trace("kt.lowerPreview") {
        val module = ctx.moduleForEditableFile(file) ?: return null
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return null
        val vf = ctx.store.vfs.fileFor(file)
        dev.ide.lang.kotlin.KotlinPerf.span("parse") { ctx.refreshParse(analyzer, file, text) }
        // Checked BEFORE lowering: refusing here is what keeps the gate cheap on the typing path, since the
        // semantic pass it runs is a fraction of the cross-file lowering it then skips.
        val blocked = dev.ide.lang.kotlin.KotlinPerf.span("gate") {
            unlowerableReason(analyzer, vf) != null || errorRefusals(analyzer, vf).isNotEmpty()
        }
        if (blocked) return null
        val model = previewModelFor(module, vf, analyzer) ?: return null
        val program = model.program
        val entry = previewEntry(program, functionName, arity) ?: return null
        val classes = model.classes
        if (previewRefusals(entry, program, classes, text).isNotEmpty()) return null
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
