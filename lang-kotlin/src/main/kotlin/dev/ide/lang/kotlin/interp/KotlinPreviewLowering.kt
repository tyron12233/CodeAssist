package dev.ide.lang.kotlin.interp

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.concurrent.ConcurrentHashMap

/**
 * Lowers a parsed Kotlin file into the [ResolvedTree] program the Compose-preview interpreter runs (see
 * docs/compose-interpreter.md). Split out of the editor analyzer: preview lowering is interpreter
 * integration with its own per-function memoization, independent of the diagnostics/completion paths that
 * share the same [KotlinSymbolService].
 */
class KotlinPreviewLowering(private val service: KotlinSymbolService) {

    /** The `@Preview @Composable` functions in [parsed] (the editor's preview targets), expanded to one entry
     *  per variant. A custom MultiPreview annotation declared in another source file is resolved through the
     *  symbol service so its constituent `@Preview`s expand too. */
    fun previews(parsed: KotlinParsedFile): List<PreviewInfo> =
        KotlinComposePreviews.find(parsed.ktFile) { entry -> resolveMultiPreview(entry, parsed.ktFile) }

    /** Expand a non-builtin, non-same-file MultiPreview annotation [entry] by locating its annotation class in
     *  the module's sources and reading the `@Preview`s it carries. One level of cross-file indirection (the
     *  common project-defined-wrapper case); null when it isn't a resolvable MultiPreview. */
    private fun resolveMultiPreview(entry: KtAnnotationEntry, file: KtFile): List<PreviewConfig>? = runCatching {
        val short = entry.shortName?.asString() ?: return null
        val fqn = annotationFqn(short, file) ?: return null
        val pf = service.sourceFileDeclaringType(fqn) ?: return null
        val declFile = parseDependency(pf) ?: return null
        val annClass = declFile.ktFile.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtClass>()
            .firstOrNull { it.isAnnotation() && (it.fqName?.asString() == fqn || it.name == short) } ?: return null
        PreviewAnnotationParser.configsForAnnotations(annClass.annotationEntries, declFile.ktFile).ifEmpty { null }
    }.getOrNull()

    /** Best-effort FQN of a type used by simple [simpleName] in [parsed] (an explicit import wins, else same
     *  package). Used to locate a `@PreviewParameter` provider class declared in a library or another file. */
    fun typeFqn(parsed: KotlinParsedFile, simpleName: String): String? = annotationFqn(simpleName, parsed.ktFile)

    /** Best-effort FQN for an annotation used by simple [short] name: an explicit import wins, else same package. */
    private fun annotationFqn(short: String, file: KtFile): String? {
        file.importDirectives.forEach { imp ->
            val fq = imp.importedFqName?.asString() ?: return@forEach
            if (fq.substringAfterLast('.') == short) return fq
        }
        val pkg = file.packageFqName.asString()
        return if (pkg.isEmpty()) short else "$pkg.$short"
    }

    /**
     * Whether [parsed] contains syntax errors (`PsiErrorElement`s). A file that doesn't parse cleanly must
     * NOT be interpreted for a preview: the error-tolerant parser still yields a whole tree, but a
     * stray/incomplete token mis-shapes declarations — e.g. `data class Project(dsad val id: …)` parses into a
     * constructor whose parameters are all shifted, so the interpreter builds objects with wrong-typed fields
     * (a `Float` slot holding a `String`). That garbage program then crashes the real Compose runtime deep in
     * its measure/semantics pass (a `ClassCastException` Compose throws AFTER the interpreter returned), which
     * no interpreter-level guard can catch. So the preview gates on this and shows "fix errors" instead.
     */
    fun hasSyntaxErrors(parsed: KotlinParsedFile): Boolean = PsiTreeUtil.hasErrorElements(parsed.ktFile)

    /** Lower every top-level function in [parsed] to a [ResolvedFunction], keyed `"name/arity"` — the program
     *  the interpreter runs a preview against (same-file composables included). */
    fun program(parsed: KotlinParsedFile): Map<String, ResolvedFunction> = loweredFor(parsed).program

    /** Lower every source class/object/enum in [parsed] to a [ResolvedClass] — the project-source types a
     *  preview's program may construct or reference (they aren't compiled at preview time, so the interpreter
     *  materializes them from this). Empty when lowering throws (the preview then hits the honest boundary for
     *  those types rather than losing the whole render). */
    fun classes(parsed: KotlinParsedFile): List<ResolvedClass> = loweredFor(parsed).classes

    // A reachable cross-file/module expansion follows at most this many OTHER files. A real preview reaches a
    // handful (the data classes / helpers it constructs); the cap is only a runaway guard for a pathological graph.
    private val maxCrossFileFiles = 200

    // Parse cache for cross-file dependency files, keyed by path → (textHash, parsed). The preview re-renders
    // per keystroke; without this each render reparses every reached sibling (parsing is the dominant cost).
    private class ParsedEntry(val textHash: Int, val parsed: KotlinParsedFile)
    private val crossFileParseCache = ConcurrentHashMap<String, ParsedEntry>()

    private fun parseDependency(pf: KotlinSymbolService.PreviewSourceFile): KotlinParsedFile? {
        val h = pf.text.hashCode()
        crossFileParseCache[pf.file.path]?.let { if (it.textHash == h) return it.parsed }
        val kt = runCatching { KotlinParserHost.parse(pf.file.name, pf.text) }.getOrNull() ?: return null
        if (PsiTreeUtil.hasErrorElements(kt)) return null // a malformed dep would lower into wrong-typed instances
        val parsed = KotlinParsedFile(kt, pf.file, 0)
        crossFileParseCache[pf.file.path] = ParsedEntry(h, parsed)
        return parsed
    }

    /** Lower a single source file [pf] (its top-level functions + classes) to a path-tagged [PreviewFileModel]
     *  — the per-file primitive the cross-file/module expander merges. No further expansion (the expander drives
     *  that). Null when the file doesn't parse cleanly. */
    fun loweredFile(pf: KotlinSymbolService.PreviewSourceFile): PreviewFileModel? {
        val parsed = parseDependency(pf) ?: return null
        val low = loweredFor(parsed)
        return PreviewFileModel(pf.file.path, low.program, low.classes)
    }

    /** This module's [PreviewDeclProvider]: resolves + lowers a reached type/function declared in THIS module
     *  (via its [KotlinSymbolService]) — what the same-module [crossFileModel] expands over. The cross-MODULE
     *  path builds its own ownership-routed provider in `IdeServices` instead. */
    fun declProvider(): PreviewDeclProvider = object : PreviewDeclProvider {
        override fun fileDeclaringType(fqn: String): PreviewFileModel? =
            service.sourceFileDeclaringType(fqn)?.let(::loweredFile)

        override fun filesDeclaringFunction(name: String): List<PreviewFileModel> =
            service.sourceFilesDeclaringFunction(name).mapNotNull(::loweredFile)
    }

    /** The entry file's own lowered program + classes (no cross-file expansion) — the seed [expandPreviewModel]
     *  grows from. Used by the cross-module preview path, which supplies its own multi-module provider. */
    fun loweredEntryFile(entryParsed: KotlinParsedFile): PreviewFileModel {
        val entry = loweredFor(entryParsed)
        return PreviewFileModel(entryParsed.file.path, entry.program, entry.classes)
    }

    /**
     * The preview program + source classes for [entryParsed], EXPANDED across files: every project-source type
     * the preview constructs/references and every top-level function it calls that is declared in ANOTHER file
     * is lowered and merged in (transitively), so the interpreter can build a `data class` or call a helper
     * defined elsewhere instead of failing to construct it (the multi-file preview case).
     *
     * Same-MODULE expansion (this analyzer's [declProvider]). A cross-module source dependency is followed by
     * the host wiring (`IdeServices`), which builds an ownership-routed provider over the dependency-module
     * closure and calls [expand] directly. The entry file's declarations win on collision.
     */
    fun crossFileModel(entryParsed: KotlinParsedFile): PreviewModel =
        expandPreviewModel(loweredEntryFile(entryParsed), maxCrossFileFiles, declProvider())

    /** [expandPreviewModel] over a host-supplied [provider], seeded from [seed] (the entry file). The
     *  cross-MODULE entry point: `IdeServices` supplies a provider that LOCATES a reached `data class`/helper
     *  across the dependency-module closure and LOWERS it with its owning module's analyzer. */
    fun expand(seed: PreviewFileModel, provider: PreviewDeclProvider): PreviewModel =
        expandPreviewModel(seed, maxCrossFileFiles, provider)

    // The lowered preview program + classes, cached per file. The preview re-renders on every keystroke AND
    // redundantly without a text change (the pane renders light + dark frames, detection runs alongside render,
    // and zoom/device switches re-fire) — PSI→ResolvedTree lowering (overload resolution against the classpath)
    // is the dominant interpreter-side cost, so it's memoized. Granularity is PER FUNCTION: a function's
    // lowering depends only on its own body + the file's *signatures* (a callee's params/return, a class header,
    // a top-level val's type), so editing one function's BODY (the hot case — typing inside a @Composable) only
    // re-lowers that function and reuses every sibling + the classes. `fileSigHash` is the file text with all
    // top-level function bodies stripped: it changes on any signature/import/class edit (→ re-lower everything,
    // conservative) but NOT on a body edit. A classpath change disposes the analyzer (host's invalidateAnalyzers),
    // dropping this with it. Cross-file source edits are best-effort (unchanged from the prior per-file cache).
    // textHash + startOffset: a function is reused only if its text AND its position are unchanged. The
    // offset guard keeps the lowered tree's SourceSpans valid — an edit that SHIFTS a sibling (e.g. typing in
    // an earlier function) moves its offset, so it re-lowers with fresh spans rather than serving stale ones.
    private class FnEntry(val textHash: Int, val startOffset: Int, val fn: ResolvedFunction)
    private class Lowered(
        val fileSigHash: Int,
        val functions: Map<String, FnEntry>,
        val classes: List<ResolvedClass>,
    ) {
        val program: Map<String, ResolvedFunction> by lazy { functions.mapValues { it.value.fn } }
    }
    private val loweredCache = ConcurrentHashMap<String, Lowered>()

    /** The file text with every TOP-LEVEL function body elided — a hash of everything a sibling's lowering can
     *  depend on (signatures, imports, properties, class bodies). Stable across function-body edits. */
    private fun fileSignatureHash(ktFile: KtFile): Int {
        val text = ktFile.text
        val sb = StringBuilder(text.length)
        var pos = 0
        for (fn in ktFile.declarations.filterIsInstance<KtNamedFunction>()) {
            val body = fn.bodyExpression ?: continue
            val r = body.textRange
            if (r.startOffset >= pos) { sb.append(text, pos, r.startOffset); pos = r.endOffset }
        }
        sb.append(text, pos, text.length)
        return sb.toString().hashCode()
    }

    private fun loweredFor(parsed: KotlinParsedFile): Lowered {
        val sigHash = fileSignatureHash(parsed.ktFile)
        val prev = loweredCache[parsed.file.path]
        val sigMatch = prev != null && prev.fileSigHash == sigHash
        // Reuse the classes whole when no signature/class-body changed (only function bodies can change without
        // moving sigHash, and those never affect a class's lowering); else build the resolver lazily and re-lower.
        val resolver by lazy(LazyThreadSafetyMode.NONE) { KotlinTreeResolver(parsed.ktFile, parsed, service) }
        val functions = parsed.ktFile.declarations.filterIsInstance<KtNamedFunction>().associate { fn ->
            val ownHash = fn.text.hashCode()
            val start = fn.textRange.startOffset
            val key = "${fn.name}/${fn.valueParameters.size}"
            val reused = if (sigMatch) prev!!.functions[key]?.takeIf { it.textHash == ownHash && it.startOffset == start } else null
            key to (reused ?: FnEntry(ownHash, start, lowerOneFunction(resolver, fn)))
        }
        val classes = if (sigMatch) prev!!.classes else runCatching { resolver.lowerClasses() }.getOrDefault(emptyList())
        return Lowered(sigHash, functions, classes).also { loweredCache[parsed.file.path] = it }
    }

    private fun lowerOneFunction(
        resolver: KotlinTreeResolver,
        fn: KtNamedFunction,
    ): ResolvedFunction = try {
        resolver.lowerFunction(fn)
    } catch (t: Throwable) {
        // A resolver gap can THROW (an unhandled PSI shape, a null in inference) rather than produce an
        // Unsupported node — which would lose the whole file's lowering and leave the preview with no
        // reason. Turn it into a diagnostic so the cause is reported, not swallowed.
        val span = SourceSpan(fn.textRange.startOffset, fn.textRange.endOffset)
        val reason = "lowering failed (${t::class.java.simpleName}): ${t.message ?: "no message"}"
        val params = fn.valueParameters.mapIndexed { i, p ->
            RParam(SlotId(i), p.name ?: "_", null)
        }
        ResolvedFunction(
            fn.name ?: "?", params,
            RNode.Unsupported(reason, fn.name ?: "", span),
            listOf(LoweringDiagnostic(reason, span)),
        )
    }
}
