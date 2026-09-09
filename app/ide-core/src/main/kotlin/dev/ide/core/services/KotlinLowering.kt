package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.interp.api.LowerRequest
import dev.ide.interp.api.LowerResult
import dev.ide.interp.impl.LoweredKotlinProgram
import dev.ide.lang.kotlin.KotlinLanguageBackend
import dev.ide.lang.kotlin.KotlinSourceAnalyzer
import dev.ide.lang.kotlin.interp.PreviewModel
import dev.ide.lang.kotlin.interp.ResolvedClass
import dev.ide.lang.kotlin.interp.ResolvedFunction
import dev.ide.lang.kotlin.interp.reachableSourceClasses
import dev.ide.lang.kotlin.interp.reachableSourceFunctions
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.model.Module
import dev.ide.vfs.VirtualFile
import java.nio.file.Paths

/**
 * Lower a Kotlin file (and everything reachable from it) for the interpreter.
 *
 * The cross-MODULE-expanded model for [vf], already parsed in [entry]'s analyzer: seed from the entry file,
 * then run the reachable-declaration expansion across [module] PLUS its transitive dependency MODULES, so a
 * `data class`/helper declared in a dependency module is lowered and merged, and the interpreter can
 * construct or call it rather than only same-module siblings.
 *
 * Resolution is OWNERSHIP-routed: a module's source model already spans its dependency modules' sources, so
 * several modules' analyzers can SEE the same dependency file. We LOCATE a reached declaration through any
 * module that can see it (the entry module first), then LOWER it with the analyzer of the module that
 * actually OWNS the file (its own source root contains it), so a dependency file resolves against ITS
 * module's full classpath, not the entry module's narrower view.
 *
 * Shared by the Compose `@Preview` path ([ComposePreviewService]) and the plugin-facing interpreter
 * ([KotlinProgramLowering]), because the work is the same for both: what differs is only which declaration is
 * the entry and how strictly an imperfect lowering is judged.
 */
internal fun loweredModelFor(
    ctx: EngineContext,
    module: Module,
    vf: VirtualFile,
    entry: KotlinSourceAnalyzer,
): PreviewModel? {
    val kotlinModules = ctx.moduleBuildClosure(module).mapNotNull { m ->
        (ctx.analyzerFor(m, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer)?.let {
            Triple(m, it, ctx.sourceRoots(m).map { r -> r.normalize() })
        }
    }

    // Lazy handles: the expansion pulls exactly the reached declaration out of the located file, so following
    // one helper into a big screen file doesn't lower its unrelated siblings (the cold-preview dominant cost
    // on a big project).
    fun lazyByOwner(pf: KotlinSymbolService.PreviewSourceFile): dev.ide.lang.kotlin.interp.PreviewLazyFile? {
        val path = runCatching { Paths.get(pf.file.path).normalize() }.getOrNull()
        val owner = path?.let { p ->
            kotlinModules.firstOrNull { (_, _, roots) -> roots.any { p.startsWith(it) } }?.second
        }
        return (owner ?: entry).lazyLoweredFile(pf)
    }

    val provider = object : dev.ide.lang.kotlin.interp.LazyPreviewDeclProvider {
        override fun fileDeclaringType(fqn: String): dev.ide.lang.kotlin.interp.PreviewLazyFile? =
            kotlinModules.firstNotNullOfOrNull { (_, a, _) -> a.findDeclaringTypeFile(fqn) }
                ?.let(::lazyByOwner)

        override fun filesDeclaringFunction(name: String): List<dev.ide.lang.kotlin.interp.PreviewLazyFile> =
            kotlinModules.flatMap { (_, a, _) -> a.findDeclaringFunctionFiles(name) }
                .distinctBy { it.file.path }.mapNotNull(::lazyByOwner)
    }
    return entry.lowerFileWithDeps(vf, provider)
}

/**
 * The engine half of the plugin-facing interpreter: lowers whatever declaration a plugin names, rather than
 * the `@Preview` composable [ComposePreviewService] is built around.
 *
 * The differences from the Compose path are the interesting part of this class, and they are all about what a
 * plugin needs that a `@Preview` does not:
 *
 *  - the entry may be a **type**, not only a function, because a framework's entry point is usually a class
 *    the framework instantiates and drives (`ApplicationListener`, `Screen`, `Activity`-shaped things);
 *  - a "not yet" answer is distinguished from a failure, because a plugin has to know whether to retry;
 *  - a reason is always produced. The Compose path answers null and asks a second method for diagnostics,
 *    which is fine for a host that owns both calls and useless across an API boundary.
 */
internal class KotlinProgramLowering(private val ctx: EngineContext) {

    fun lower(request: LowerRequest): LowerResult {
        val file = request.file
        val module = ctx.moduleForEditableFile(file)
            ?: return LowerResult.NotReady("no module in the open project owns ${file.fileName}")
        val analyzer = ctx.analyzerFor(module, KotlinLanguageBackend.LANGUAGE_ID) as? KotlinSourceAnalyzer
            ?: return LowerResult.Failed(listOf("${file.fileName} is not a Kotlin file"))
        val vf = ctx.store.vfs.fileFor(file)

        val text = request.text ?: runCatching { vf.readText().toString() }.getOrNull()
            ?: return LowerResult.Failed(listOf("${file.fileName} could not be read"))
        ctx.refreshParse(analyzer, file, text)

        // A file with syntax errors mis-shapes declarations when parsed error-tolerantly, so interpreting it
        // runs a garbage program. Refuse rather than run.
        if (analyzer.hasSyntaxErrors(vf)) {
            return LowerResult.Failed(listOf("${file.fileName} has syntax errors; fix them to run it"))
        }
        // While the classpath callable index is still BUILDING, every library call lowers to "no candidates",
        // so the program would look broken for a reason that passes on its own. This is the case a caller must
        // retry rather than report, hence NotReady. A FINISHED-but-partial index (a jar that could not be
        // decoded) never flips ready and must not wedge here, so the gate is "still building", not "ready".
        if (analyzer.classpathIndexBuilding()) {
            return LowerResult.NotReady("preparing to run: still indexing this module's dependencies")
        }

        val model = loweredModelFor(ctx, module, vf, analyzer)
            ?: return LowerResult.NotReady("${file.fileName} is not parsed yet")
        val program = model.program
        val classes = model.classes

        val fn = entryFunction(program, request.entry, request.arity)
        if (fn != null) return lowered(fn, null, program, classes, request.strict)

        val type = entryType(classes, request.entry)
        if (type != null) return lowered(null, type, program, classes, request.strict)

        return LowerResult.Failed(
            listOf(
                "`${request.entry}` is not a top-level function or class in ${file.fileName} " +
                    "(it declares ${declared(program, classes)})"
            )
        )
    }

    /** Build the program, or refuse it when the entry itself, or (under [strict]) anything it reaches, did not
     *  lower cleanly. */
    private fun lowered(
        fn: ResolvedFunction?,
        type: ResolvedClass?,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass>,
        strict: Boolean,
    ): LowerResult {
        // The ENTRY is refused even in the tolerant mode: gap-skipping keeps a broken statement from costing a
        // whole render, but an entry that did not lower has no body worth running.
        if (fn != null && !fn.isComplete) {
            return LowerResult.Failed(fn.diagnostics.map { "in ${fn.name}: ${it.reason}" })
        }
        if (type != null && !type.isComplete) {
            return LowerResult.Failed(problemsOf(type))
        }

        val problems = when {
            fn != null -> reachableProblems(fn, program, classes)
            // A type entry has no call graph to walk from (reachability is seeded by a function body), so its
            // own members are what can be judged. A helper it calls is reported when it is reached, by the
            // session, rather than here.
            else -> problemsOf(type!!)
        }
        if (strict && problems.isNotEmpty()) return LowerResult.Failed(problems)

        return LowerResult.Lowered(
            LoweredKotlinProgram(
                functions = program,
                classes = classes,
                entryFunction = fn,
                entryType = type,
                problems = problems,
            )
        )
    }

    /** Everything reachable from [fn] that lowered imperfectly, phrased for display. A reachable declaration
     *  is what actually matters: an unrelated broken class in the same file can never be touched by this run. */
    private fun reachableProblems(
        fn: ResolvedFunction,
        program: Map<String, ResolvedFunction>,
        classes: List<ResolvedClass>,
    ): List<String> {
        val reachedTypes = reachableSourceClasses(fn, program, classes)
        val classProblems = classes.filter { it.fqn in reachedTypes && !it.isComplete }.flatMap { problemsOf(it) }
        val fnProblems = reachableSourceFunctions(fn, program, classes)
            .filter { it !== fn && !it.isComplete }
            .flatMap { f -> f.diagnostics.map { "in ${f.name}: ${it.reason}" } }
        return classProblems + fnProblems
    }

    private fun problemsOf(cls: ResolvedClass): List<String> =
        (cls.diagnostics + cls.methods.values.flatMap { it.diagnostics })
            .map { "in ${cls.simpleName}: ${it.reason}" }

    /** The lowered function for [name] at [arity], falling back to any arity of that name so a stale arity
     *  still resolves (the same tolerance the `@Preview` path has). */
    private fun entryFunction(
        program: Map<String, ResolvedFunction>, name: String, arity: Int,
    ): ResolvedFunction? = program["$name/$arity"]
        ?: program.entries.firstOrNull { it.key.substringBeforeLast('/') == name }?.value

    private fun entryType(classes: List<ResolvedClass>, name: String): ResolvedClass? =
        classes.firstOrNull { it.fqn == name }
            ?: classes.firstOrNull { it.simpleName == name.substringAfterLast('.') }

    /** What the file actually declares, so a wrong entry name is answerable rather than merely refused. */
    private fun declared(program: Map<String, ResolvedFunction>, classes: List<ResolvedClass>): String {
        val functions = program.keys.map { it.substringBeforeLast('/') }.distinct().sorted()
        val types = classes.map { it.simpleName }.distinct().sorted()
        return (functions + types).joinToString().ifEmpty { "nothing interpretable" }
    }
}
