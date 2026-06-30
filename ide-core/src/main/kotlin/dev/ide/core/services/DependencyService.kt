package dev.ide.core.services

import dev.ide.android.support.AndroidVariants
import dev.ide.android.support.gms.GoogleServices
import dev.ide.android.support.tools.AarExtractor
import dev.ide.core.DependencyPartition
import dev.ide.core.EngineContext
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dev.ide.deps.ArtifactKind
import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.deps.ResolutionResult
import dev.ide.deps.impl.DEFAULT_REPOSITORIES
import dev.ide.deps.impl.MavenDependencyResolver
import dev.ide.deps.impl.ResolverCache
import dev.ide.lang.jdt.JdtSourceAnalyzer
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.Exclusion
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.PlatformDependency
import dev.ide.model.Project
import dev.ide.model.SdkDependency
import dev.ide.model.module
import dev.ide.platform.Disposable
import dev.ide.platform.ProgressReporter
import dev.ide.platform.log.Log
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiDepKind
import dev.ide.ui.backend.UiDepModule
import dev.ide.ui.backend.UiDependencyNode
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiRepository
import dev.ide.ui.backend.UiUnresolvedDependency
import dev.ide.ui.backend.UiVersionConflict
import dev.ide.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * WORKSPACE-scoped engine service: Maven dependency management — add/resolve/conflict, BOM platforms,
 * one-click Firebase/GMS, local libraries, repositories, module-on-module deps, and the per-project conflict
 * policy. Carved out of [dev.ide.core.IdeServices]. Owns the Maven resolver + the background resolve scope
 * (Disposable: its scope is cancelled when the workspace container disposes). Resolution results are
 * persisted to the model (the LibraryTable), which the build/editor read — so consumers stay decoupled from
 * the resolver. Reaches shared infrastructure (model, prefs, caches) through [EngineContext].
 */
internal class DependencyService(private val ctx: EngineContext) : Disposable {

    /** Per-project settings key (in `.platform/settings.properties`) for the dependency conflict policy. */
    private val CONFLICT_POLICY_PREF = "build.conflictPolicy"

    // ---- dependency management ----

    /**
     * The Maven resolver, caching resolved-deps under the shared caches root when the host supplies one (so
     * every project shares one download store) or the workspace itself otherwise. Downloaded jars/
     * classes-from-aars are wrapped as the store's [VirtualFile]s (which handle any absolute path, in or
     * out of the workspace) so they flow straight into the model's [LibraryTable] →
     * [dev.ide.model.ClasspathSnapshot] → build + analysis, like any other library.
     */
    private val depsResolver = MavenDependencyResolver(
        cache = ResolverCache(ctx.sharedCachesRoot ?: ctx.store.rootPath),
        fileFor = { p -> ctx.store.vfs.fileFor(p) },
    )

    private val _depsState = MutableStateFlow(DepsResolveState())
    val depsState: StateFlow<DepsResolveState> get() = _depsState

    /** Upper bound on the resolve log kept in [depsState] (the expandable detail in the editor's resolve bar). */
    private val MAX_DEPS_LOG = 300

    /** Append [line] to a resolve log, dropping a consecutive duplicate and capping length so a large
     *  transitive closure can't grow it without bound. */
    private fun appendDepsLog(log: List<String>, line: String?): List<String> {
        if (line.isNullOrBlank() || line == log.lastOrNull()) return log
        val next = log + line
        return if (next.size > MAX_DEPS_LOG) next.subList(
            next.size - MAX_DEPS_LOG, next.size
        ) else next
    }

    private fun depsProgress(): ProgressReporter = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {
            _depsState.update {
                it.copy(
                    fraction = fraction,
                    message = message ?: it.message,
                    log = appendDepsLog(it.log, message)
                )
            }
        }

        override fun checkCanceled() {}
        override val isCanceled: Boolean get() = false
    }

    /** Like [depsProgress] but leaves the fraction to the caller — the template batch owns the overall
     *  i/size progress while the resolver's per-artifact messages still stream into the message line + log. */
    private fun depsLogProgress(): ProgressReporter = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {
            if (message != null) _depsState.update {
                it.copy(
                    message = message, log = appendDepsLog(it.log, message)
                )
            }
        }

        override fun checkCanceled() {}
        override val isCanceled: Boolean get() = false
    }

    private val NoProgress = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled: Boolean get() = false
    }

    // --- deferred template-dependency resolution (project-scoped, started after the project is opened) ---

    /** A project-scoped scope for background resolution, cancelled in [close] (so leaving a project stops it). */
    private val depsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Template-declared deps to resolve after open (e.g. the Compose AAR graph), set by [createProjectAt]. */
    @Volatile
    private var pendingDeps: List<dev.ide.model.template.TemplateDependency> = emptyList()
    private val pendingStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reconcileStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Fingerprint marker recording the declared-dependency set last fully reconciled — so reconciliation
     *  runs once per dep-set change, not on every open. */
    private val reconcileMarker: java.nio.file.Path get() = ctx.store.rootPath.resolve(".platform/.deps-reconciled")

    private val depsLog = Log.logger("deps")

    /** Why each currently-unresolved declared coordinate failed, from the most recent resolve. The KEY SET is
     *  the authoritative "unresolved declared deps" — populated only from the resolver's
     *  [ResolutionResult.unresolved] (a coordinate it genuinely couldn't fetch), so a variant dep that
     *  resolves without its own artifact is never wrongly listed. Read by [computeUnresolved]/[declaredUnresolved]. */
    private val unresolvedReasons = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Fallback reason when no resolve has run yet this session (e.g. just opened a project with a missing
     *  closure). Still actionable: the user can Retry once the network is back. */
    private val DEFAULT_UNRESOLVED_REASON =
        "Not resolved. Check your internet connection, then tap Retry."

    /** Modules carrying at least one Maven-coordinate library dependency — the reconcilable set. */
    private fun mavenDepModules(): List<Module> = ctx.modules().filter { m ->
        m.dependencies.any { it is LibraryDependency && parseCoordinate(it.library.name) != null }
    }

    /** Fingerprint of the declared library-dependency set (the reconcile marker's contents). */
    private fun reconcileFingerprint(mods: List<Module>): String = mods.flatMap { m ->
        m.dependencies.filterIsInstance<LibraryDependency>()
            .map { "${m.id.value}|${it.library.name}" }
    }.sorted().joinToString("\n")

    /** Record that the current declared set has been fully resolved, so the next open skips the re-walk. */
    private fun markReconciled(fingerprint: String) = runCatching {
        java.nio.file.Files.createDirectories(reconcileMarker.parent)
        reconcileMarker.writeText(fingerprint)
    }

    // --- dependency-health (resolved vs unresolved) error state ----------------------------------

    /** True if any resolved library's jar is missing on disk (a cleared cache / partial write) — the signal
     *  that a "reconciled" marker is stale and the closure must be re-resolved. Variant-safe: it inspects the
     *  libraries that actually exist, never a declared coordinate that legitimately has no library of its own. */
    private fun hasMissingLibraryFiles(): Boolean {
        val tables =
            listOf(ctx.store.workspace.libraryTable) + ctx.store.workspace.projects.map { it.libraryTable }
        return tables.flatMap { it.libraries }.any { lib ->
            lib.classesRoots.any {
                runCatching { !Files.exists(Paths.get(it.path)) }.getOrDefault(
                    true
                )
            }
        }
    }

    /** The declared Maven coordinates of [module] the LAST resolve could not resolve (the build-blocking set).
     *  Driven by the resolver's own verdict ([unresolvedReasons] ← [ResolutionResult.unresolved]), NOT by
     *  per-coordinate library presence: a Gradle-variant dependency (e.g. `androidx.compose.ui:ui`, whose code
     *  ships in `ui-android`) resolves with no library of its own and must NOT be treated as unresolved. */
    fun declaredUnresolved(module: Module): List<String> =
        module.dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }
            .filter { unresolvedReasons.containsKey(it) }

    /** Every unresolved declared dependency across the workspace, with its module + best-effort reason. */
    private fun computeUnresolved(): List<UiUnresolvedDependency> = ctx.modules().flatMap { m ->
        declaredUnresolved(m).map { coord ->
            UiUnresolvedDependency(
                m.name, coord, unresolvedReasons[coord] ?: DEFAULT_UNRESOLVED_REASON
            )
        }
    }

    /** Publish the current dependency-health into [depsState] (the persistent project error state). */
    private fun publishDependencyHealth() {
        val unresolved = computeUnresolved()
        _depsState.update { it.copy(unresolved = unresolved) }
    }

    /** Heuristic reason for an unresolved coordinate, from one resolve's outcome: nothing resolved at all
     *  reads as a connectivity failure; a partial resolve points at the coordinate/repository. */
    private fun unresolvedReasonFor(anyResolved: Boolean): String =
        if (!anyResolved) "Couldn't reach the repositories. Check your internet connection, then tap Retry."
        else "Couldn't download from the configured repositories. Check the version/coordinate or your connection, then tap Retry."

    /** Fold one module-graph resolve into [unresolvedReasons], keyed by declared library NAME. A declared
     *  coordinate is unresolved IFF the resolver itself reported it in [ResolutionResult.unresolved] (a POM
     *  or download that genuinely failed). A coordinate that resolved without an artifact of its own (a
     *  Gradle variant like `compose.ui:ui` → `ui-android`) is NOT in `unresolved`, so it is correctly cleared
     *  — the previous "is its own artifact in `resolved`" test wrongly flagged such variants. */
    private fun recordResolveReasons(
        declared: List<Pair<String, Coordinate>>, result: ResolutionResult
    ) {
        val failedGAs = result.unresolved.map { "${it.group}:${it.name}" }.toSet()
        val anyResolved = result.resolved.isNotEmpty()
        for ((name, c) in declared) {
            if ("${c.group}:${c.name}" in failedGAs) unresolvedReasons[name] =
                unresolvedReasonFor(anyResolved)
            else unresolvedReasons.remove(name)
        }
    }


    /**
     * Heal stale/incomplete persisted dependency closures in the background, once per open. A project's
     * `libraries.json` can hold an INCOMPLETE closure: an artifact that failed to download during the
     * original resolve was historically dropped silently, so e.g. base `androidx.activity:activity`
     * (`ComponentActivity`'s home) could be absent from `activity-compose`'s closure, and nothing
     * re-resolved on open — completion then can't resolve the type. This re-resolves each declared Maven
     * dependency (cache-first, so cheap when already healthy) and rebuilds any closure that differs from a
     * fully-successful fresh resolve. Bounded by a fingerprint marker so the graph isn't re-walked on every
     * open; only marks done when *every* dependency resolved completely, so an offline open retries next
     * time instead of locking in a partial state. Never clobbers a good closure with an incomplete one.
     */
    fun startDependencyReconciliation() {
        if (!reconcileStarted.compareAndSet(false, true)) {
            depsLog.info("reconcile: already started this session → skip"); return
        }
        val mods = mavenDepModules()
        if (mods.isEmpty()) {
            depsLog.info("reconcile: no modules with Maven deps → skip"); return
        }
        val fingerprint = reconcileFingerprint(mods)
        // Trust the marker only when the declared deps are ACTUALLY satisfied on disk. A marker can go stale
        // (the dep cache was cleared, a partial write) — then the closure is missing yet the marker says
        // "done", so reconciliation must re-resolve despite it or the project never heals.
        if (runCatching { reconcileMarker.readText() }.getOrNull() == fingerprint && !hasMissingLibraryFiles()) {
            depsLog.info("reconcile: ${mods.size} module(s) already reconciled, all present → skip")
            return
        }
        depsLog.info("reconcile: ${mods.size} module(s) with Maven deps → resolving")

        depsScope.launch {
            var changedAny = false
            var allComplete = true
            for (m in mods) {
                // finalize=false: defer the one save/invalidate/reindex to after the whole batch.
                val asm = runCatching {
                    assembleModuleClasspath(
                        m, NoProgress, finalize = false
                    )
                }.onFailure { depsLog.warn("reconcile ${m.name} aborted: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                if (asm == null || asm.unresolved.isNotEmpty()) allComplete =
                    false // partial → retry next open
                if (asm?.changed == true) changedAny = true
            }
            if (changedAny) {
                ctx.store.save(); ctx.invalidateAnalyzers(); ctx.resyncIndex()
            }
            if (allComplete) markReconciled(fingerprint)
            // Refresh the project error state from what's now on disk (reasons stamped during the resolves).
            publishDependencyHealth()
        }
    }

    private data class ModuleAssembly(
        val changed: Boolean, val unresolved: List<Coordinate>, val resolvedCount: Int
    )

    /**
     * Resolve a module's ENTIRE declared Maven-dependency set as ONE graph (not each dependency in isolation),
     * so conflict resolution spans the whole graph: one version per `group:name`, and a transitive that only a
     * superseded version pulled in is pruned — exactly what `gradle`/`maven` produce. The single unified closure
     * is partitioned back across the declared dependencies (each artifact assigned to the first declarer, in
     * order, whose `dependsOn` chain reaches it), so the per-library model is preserved while the UNION of the
     * libraries is precisely the whole-graph closure — no duplication, no independent-resolution drift. A
     * library is rewritten only when its partition actually changes; a dependency whose primary didn't resolve
     * (offline) is left intact rather than emptied. Returns the unresolved coordinates so a partial resolve is
     * surfaced and never treated as complete.
     */
    private suspend fun assembleModuleClasspath(
        module: Module, progress: ProgressReporter, finalize: Boolean
    ): ModuleAssembly {
        val libDeps = module.dependencies.filterIsInstance<LibraryDependency>().mapNotNull { dep ->
            parseInputCoordinate(dep.library.name)?.let {
                Triple(
                    dep.library.name, it, dep.exclusions
                )
            }
        }.distinctBy { it.first }
        val directs = libDeps.map { it.first to it.second }
        if (directs.isEmpty()) return ModuleAssembly(false, emptyList(), 0)

        // Per-declaration exclusions, keyed by the (parsed) coordinate the resolver is seeded with, so each
        // declaration's excludes prune only its own subtree (Gradle/Maven per-declaration semantics).
        val exclusions =
            libDeps.filter { it.third.isNotEmpty() }.associate { it.second to it.third }
        val result = depsResolver.resolve(
            directs.map { it.second },
            currentRepositories(),
            conflictPolicy,
            progress,
            platforms = declaredPlatforms(module),
            exclusions = exclusions,
        )
        val byGa = result.resolved.associateBy { it.coordinate.group to it.coordinate.name }
        val partition = DependencyPartition.partition(directs, result.resolved)
        // Stamp a why-it-failed on each declared coordinate that didn't resolve (cleared if it did), so the
        // project error state can explain itself; keyed by the declared library name.
        recordResolveReasons(directs, result)
        // "unresolved" = what the resolver itself couldn't resolve, NOT a declared coord with an empty
        // partition (a Gradle-variant dep is shared with an earlier declarer and resolves fine).
        val failedGAs = result.unresolved.map { "${it.group}:${it.name}" }.toSet()
        val unresolvedDeclared =
            directs.filter { (_, c) -> "${c.group}:${c.name}" in failedGAs }.map { it.first }
        depsLog.info(
            "assemble ${module.name}: ${directs.size} declared, ${result.resolved.size} resolved" + if (unresolvedDeclared.isNotEmpty()) ", UNRESOLVED ${unresolvedDeclared.joinToString()}" else "",
        )

        var changed = false
        for ((libName, coord) in directs) {
            val artifacts = partition[libName].orEmpty()
            if (artifacts.isEmpty()) continue   // primary didn't resolve (offline) → keep any existing closure
            val freshClasses = artifacts.map { it.classesRoot.path }.toSet()
            // The whole closure's `-sources.jar`s — direct AND transitive — so go-to-source, real parameter
            // names, and javadoc/KDoc resolve for every type the library can reach, not just the declared
            // coordinate. (`sourceAttachments` → JdtSourceAnalyzer's resolver + the source-doc index.) Included
            // in the up-to-date check so an existing library whose classes already match but is missing the
            // transitive sources (the prior behavior, or an offline first resolve) heals on reconcile.
            val freshSources = artifacts.mapNotNull { it.sourcesRoot?.path }.toSet()
            val existing = findLibrary(libName)
            if (existing?.classesRoots?.map { it.path }
                    ?.toSet() == freshClasses && existing.sourcesRoots.map { it.path }
                    .toSet() == freshSources) continue // already correct
            val primary = byGa[coord.group to coord.name]
            ctx.store.workspace.libraryTable.create(libName).apply {
                kind = if (primary?.kind == ArtifactKind.AAR) LibraryKind.AAR else LibraryKind.JAR
                artifacts.forEach { a ->
                    addClassesRoot(a.classesRoot)
                    a.sourcesRoot?.let { addSourcesRoot(it) }
                }
                commit()
            }
            changed = true
        }
        if (changed && finalize) {
            ctx.store.save(); ctx.invalidateAnalyzers(); ctx.resyncIndex()
        }
        return ModuleAssembly(changed, result.unresolved, result.resolved.size)
    }

    /**
     * Resolve the template's declared dependencies in the background — started by the host **once the project
     * is open** (not during creation), so a large/slow closure never blocks creation and the user can use the
     * rest of the app while it streams in.
     *
     * The declarations themselves are NOT written here — [createProjectAt] already persisted them to
     * `module.toml` (the source of truth for what the project requires), independent of resolution. This step
     * only assembles the declared set into `libraries.json`, as ONE whole-graph resolve per affected module
     * (transitives conflict-resolved across the whole graph, like gradle/maven). A module that fails to
     * resolve fully keeps its declarations intact, so the next open's [startDependencyReconciliation] retries
     * it — a transient (offline) failure never silently drops a declared dependency. Idempotent; progress is
     * published on [depsState] (the same flow the Dependencies screen and the editor's resolve bar observe).
     */
    fun startPendingDependencyResolution() {
        val deps = pendingDeps
        depsLog.info("startPendingDependencyResolution: ${deps.size} pending template dep(s)${if (deps.isEmpty()) " → reconcile" else ""}")
        // No template deps to resolve (the normal case for an already-created project that's just been
        // opened) → instead reconcile the persisted closures, which heals an incomplete one baked by an
        // earlier partial resolve (see [startDependencyReconciliation]).
        if (deps.isEmpty()) {
            // Reflect the persisted library state immediately (so a project opened with a missing closure
            // shows the error banner at once), then heal/refine in the background.
            publishDependencyHealth()
            startDependencyReconciliation(); return
        }
        if (!pendingStarted.compareAndSet(false, true)) return
        pendingDeps = emptyList()
        val moduleNames = deps.map { it.module }.distinct()
        depsScope.launch {
            _depsState.value = DepsResolveState(
                resolving = true,
                message = "Resolving project dependencies…",
                fraction = 0.0,
                log = listOf("Resolving project dependencies…")
            )
            var changedAny = false
            var allComplete = true
            try {
                moduleNames.forEachIndexed { i, name ->
                    val module = ctx.modules().firstOrNull { it.name == name } ?: return@forEachIndexed
                    _depsState.update {
                        val m = "Resolving dependencies for $name  (${i + 1}/${moduleNames.size})"
                        it.copy(
                            message = m,
                            fraction = i.toDouble() / moduleNames.size,
                            log = appendDepsLog(it.log, m)
                        )
                    }
                    // finalize=false: defer the save/invalidate/reindex; do it once after the whole batch.
                    // depsLogProgress streams the resolver's per-artifact detail into the log.
                    val asm = runCatching {
                        assembleModuleClasspath(module, depsLogProgress(), finalize = false)
                    }.onFailure { depsLog.warn("pending resolve $name aborted: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                    if (asm == null || asm.unresolved.isNotEmpty()) allComplete = false
                    if (asm?.changed == true) changedAny = true
                }
                if (changedAny) {
                    ctx.store.save()
                    ctx.invalidateAnalyzers()
                    ctx.resyncIndex()
                }
                // Only mark the declared set reconciled when it resolved completely; a partial resolve
                // (offline) leaves the marker absent so the next open retries via reconciliation.
                if (allComplete) markReconciled(reconcileFingerprint(mavenDepModules()))
            } finally {
                // Publish the final dependency-health alongside resolving=false: any dep that didn't resolve
                // stays as the persistent error state (with the reason recorded during the resolve).
                _depsState.update {
                    it.copy(
                        resolving = false,
                        fraction = 1.0,
                        message = "Dependencies resolved",
                        log = appendDepsLog(it.log, "Dependencies resolved"),
                        unresolved = computeUnresolved(),
                    )
                }
            }
        }
    }

    /**
     * Re-attempt resolving every declared dependency — the action behind the editor's unresolved-deps banner.
     * Re-walks each declared Maven dependency cache-first, rebuilds `libraries.json`, and refreshes the
     * [depsState] error state, so a project that failed to resolve (offline) recovers once the network is
     * back, without reopening. Always runs (unlike the once-per-open background paths).
     */
    suspend fun retryDependencyResolution() {
        runCatching { java.nio.file.Files.deleteIfExists(reconcileMarker) }
        val mods = mavenDepModules()
        depsLog.info("retryDependencyResolution: ${mods.size} module(s) with Maven deps")
        if (mods.isEmpty()) {
            publishDependencyHealth(); return
        }
        _depsState.value = DepsResolveState(
            resolving = true,
            message = "Resolving project dependencies…",
            fraction = 0.0,
            log = listOf("Retrying dependency resolution…"),
        )
        var changedAny = false
        var allComplete = true
        try {
            mods.forEachIndexed { i, m ->
                _depsState.update {
                    val msg = "Resolving dependencies for ${m.name}  (${i + 1}/${mods.size})"
                    it.copy(
                        message = msg,
                        fraction = i.toDouble() / mods.size,
                        log = appendDepsLog(it.log, msg)
                    )
                }
                val asm = runCatching {
                    assembleModuleClasspath(m, depsLogProgress(), finalize = false)
                }.onFailure { depsLog.warn("retry resolve ${m.name} aborted: ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
                if (asm == null || asm.unresolved.isNotEmpty()) allComplete = false
                if (asm?.changed == true) changedAny = true
            }
            if (changedAny) {
                ctx.store.save(); ctx.invalidateAnalyzers(); ctx.resyncIndex()
            }
            if (allComplete) markReconciled(reconcileFingerprint(mavenDepModules()))
        } finally {
            _depsState.update {
                it.copy(
                    resolving = false,
                    fraction = 1.0,
                    message = "Dependencies resolved",
                    log = appendDepsLog(it.log, "Dependencies resolved"),
                    unresolved = computeUnresolved(),
                )
            }
        }
    }

    /**
     * Persist the template's declared Maven dependencies into the model (→ `module.toml`) at creation, so the
     * declared set is the durable source of truth INDEPENDENT of resolution. Resolution (→ `libraries.json`)
     * runs later in the background ([startPendingDependencyResolution]); a resolution failure no longer drops
     * the declaration — it stays declared and the next open's reconciliation retries it. Dedup-guarded so a
     * dep the template's [generate] (or [ensureKotlinStdlib]) already declared isn't doubled.
     */
    private fun declareTemplateDependencies(deps: List<dev.ide.model.template.TemplateDependency>) {
        if (deps.isEmpty()) return
        var changed = false
        for ((moduleName, forModule) in deps.groupBy { it.module }) {
            val module = ctx.modules().firstOrNull { it.name == moduleName } ?: continue
            val project = ctx.projectOf(module) ?: continue
            val toAdd = forModule.filter { d ->
                module.dependencies.none { it is LibraryDependency && it.library.name == d.coordinate }
            }
            if (toAdd.isEmpty()) continue
            project.beginModification().apply {
                toAdd.forEach { d ->
                    module(module.id).addDependency(
                        LibraryDependency(LibraryRef(d.coordinate), parseScope(d.scope))
                    )
                }
                commit()
            }
            changed = true
        }
        if (changed) ctx.store.save()
    }

    /** A module can consume an Android archive (`.aar`) iff it's an Android module — facet or type. */
    private fun acceptsAar(module: Module): Boolean =
        module.facets.get(dev.ide.android.support.AndroidFacet.KEY) != null || module.type.id.startsWith(
            "android"
        )

    private fun aarReason(module: Module): String =
        "Android archives (.aar) need an Android module; '${module.name}' is a ${module.type.displayName.lowercase()}"

    private fun parseCoordinate(name: String): Coordinate? =
        name.split(":").takeIf { it.size >= 3 }?.let { Coordinate(it[0], it[1], it[2]) }

    /**
     * Parse a user-entered dependency coordinate. Accepts `group:name:version` and the versionless
     * `group:name` form (version filled from an imported platform BOM at resolve time → blank here).
     */
    private fun parseInputCoordinate(name: String): Coordinate? =
        name.split(":").map { it.trim() }.let {
            when (it.size) {
                2 -> Coordinate(it[0], it[1], "")
                3 -> Coordinate(it[0], it[1], it[2])
                else -> null
            }
        }

    /** The BOM coordinates [module] imports as platforms — the version source for its versionless deps. */
    private fun declaredPlatforms(module: Module): List<Coordinate> =
        module.dependencies.filterIsInstance<PlatformDependency>().map { it.bom }

    private fun findLibrary(name: String) = ctx.store.workspace.libraryTable.byName(name)
        ?: ctx.store.workspace.projects.firstNotNullOfOrNull { it.libraryTable.byName(name) }

    private fun scopeLabel(scope: DependencyScope): String = when (scope) {
        DependencyScope.API -> "api"
        DependencyScope.IMPLEMENTATION -> "implementation"
        DependencyScope.COMPILE_ONLY -> "compileOnly"
        DependencyScope.RUNTIME_ONLY -> "runtimeOnly"
        DependencyScope.TEST_IMPLEMENTATION -> "testImplementation"
    }

    private fun parseScope(label: String): DependencyScope =
        when (label.lowercase().replace("_", "").replace("-", "")) {
            "api" -> DependencyScope.API
            "compileonly" -> DependencyScope.COMPILE_ONLY
            "runtimeonly" -> DependencyScope.RUNTIME_ONLY
            "testimplementation", "test" -> DependencyScope.TEST_IMPLEMENTATION
            else -> DependencyScope.IMPLEMENTATION
        }

    /** Dependency-declaring modules + their build system + AAR compatibility (the screen's module switcher). */
    fun dependencyModules(): List<UiDepModule> = ctx.modules().map { m ->
        UiDepModule(
            name = m.name,
            buildSystem = ctx.projectOf(m)?.buildSystemId?.value ?: "native",
            acceptsAar = acceptsAar(m),
            dependencyCount = m.dependencies.count { it is LibraryDependency },
        )
    }

    /**
     * Resolve [moduleName]'s full dependency picture: declared entries, the transitive graph (from the
     * resolver's `dependsOn` edges), version conflicts, cycles, and per-artifact compatibility. Network
     * resolution is cached on disk; on failure it degrades to declared-only with everything unresolved.
     */
    suspend fun moduleDependencies(moduleName: String): UiModuleDeps? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        val accepts = acceptsAar(module)
        val buildSystem = ctx.projectOf(module)?.buildSystemId?.value ?: "native"

        // Declared library coordinates → resolve them together so cross-dep version conflicts surface.
        val declaredGAs = LinkedHashSet<String>()
        val scopeByGa = HashMap<String, DependencyScope>()
        val externalCoords = ArrayList<Coordinate>()
        // Per-declaration exclusions, keyed by the seeded coordinate — passed to resolve so the displayed
        // tree reflects exclusions (an excluded transitive is pruned from the closure, not shown as a node).
        val exclusionsByCoord = HashMap<Coordinate, List<Exclusion>>()
        for (entry in module.dependencies) if (entry is LibraryDependency) {
            parseCoordinate(entry.library.name)?.let { c ->
                val ga = "${c.group}:${c.name}"
                declaredGAs += ga
                scopeByGa[ga] = entry.scope
                externalCoords += c
                if (entry.exclusions.isNotEmpty()) exclusionsByCoord[c] = entry.exclusions
            }
        }

        val result = if (externalCoords.isEmpty()) null else {
            _depsState.value =
                DepsResolveState(resolving = true, message = "Resolving ${module.name}…")
            runCatching {
                depsResolver.resolve(
                    externalCoords,
                    currentRepositories(),
                    conflictPolicy,
                    depsProgress(),
                    platforms = declaredPlatforms(module),
                    exclusions = exclusionsByCoord,
                )
            }.onFailure {
                // The Dependencies-screen resolve is cancelled when the screen leaves composition; this also
                // catches an unexpected error. Logged so a "screen shows unresolved but no resolver failure"
                // case is explained (cancellation) rather than looking like a silent hang.
                depsLog.warn("moduleDependencies(${module.name}) resolve aborted: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull().also { _depsState.update { s -> s.copy(resolving = false) } }
        }

        val conflictGAs = result?.conflicts?.map { it.coordinate }?.toSet().orEmpty()
        val edges = LinkedHashMap<String, List<String>>()
        val nodes = LinkedHashMap<String, UiDependencyNode>()
        result?.resolved?.forEach { art ->
            val coordStr = art.coordinate.toString()
            val ga = "${art.coordinate.group}:${art.coordinate.name}"
            val kind = if (art.kind == ArtifactKind.AAR) UiDepKind.Aar else UiDepKind.Jar
            val compatible = kind != UiDepKind.Aar || accepts
            val declared = ga in declaredGAs
            edges[coordStr] = art.dependsOn.map { it.toString() }
            nodes[coordStr] = UiDependencyNode(
                coordinate = coordStr,
                group = art.coordinate.group,
                name = art.coordinate.name,
                version = art.coordinate.version,
                kind = kind,
                declared = declared,
                scope = if (declared) scopeByGa[ga]?.let(::scopeLabel) else null,
                compatible = compatible,
                incompatibleReason = if (!compatible) aarReason(module) else null,
                inConflict = ga in conflictGAs,
                children = art.dependsOn.map { it.toString() },
            )
        }

        // Declared roots, in declaration order — library coords (matched by group:name), module + sdk deps.
        val unresolved = result?.unresolved?.map { it.toString() }?.toMutableSet() ?: mutableSetOf()
        val declaredRoots = ArrayList<UiDependencyNode>()
        for (entry in module.dependencies) when (entry) {
            is LibraryDependency -> {
                val excl = entry.exclusions.map { it.toString() }
                val coord = parseCoordinate(entry.library.name)
                if (coord != null) {
                    val ga = "${coord.group}:${coord.name}"
                    val resolvedNode = nodes.values.firstOrNull { "${it.group}:${it.name}" == ga }
                    if (resolvedNode != null) declaredRoots += resolvedNode.copy(exclusions = excl)
                    else {
                        unresolved += entry.library.name
                        val lib = findLibrary(entry.library.name)
                        val kind =
                            if (lib?.kind == LibraryKind.AAR) UiDepKind.Aar else UiDepKind.Jar
                        val compatible = kind != UiDepKind.Aar || accepts
                        val node = UiDependencyNode(
                            coordinate = entry.library.name,
                            group = coord.group,
                            name = coord.name,
                            version = coord.version,
                            kind = kind,
                            declared = true,
                            scope = scopeLabel(entry.scope),
                            compatible = compatible,
                            incompatibleReason = if (!compatible) aarReason(module) else null,
                            exclusions = excl,
                        )
                        declaredRoots += node
                        nodes.putIfAbsent(node.coordinate, node)
                    }
                } else {
                    // A local/file-based library (non-coordinate name) — show it, no transitive resolution.
                    val lib = findLibrary(entry.library.name)
                    val kind = if (lib?.kind == LibraryKind.AAR) UiDepKind.Aar else UiDepKind.Jar
                    val compatible = kind != UiDepKind.Aar || accepts
                    val node = UiDependencyNode(
                        coordinate = entry.library.name,
                        group = "",
                        name = entry.library.name,
                        version = "",
                        kind = kind,
                        declared = true,
                        scope = scopeLabel(entry.scope),
                        compatible = compatible,
                        incompatibleReason = if (!compatible) aarReason(module) else null,
                        local = true,
                    )
                    declaredRoots += node
                    nodes.putIfAbsent(node.coordinate, node)
                }
            }

            is ModuleDependency -> {
                val node = UiDependencyNode(
                    coordinate = entry.target.value,
                    group = "",
                    name = entry.target.value,
                    version = "",
                    kind = UiDepKind.Module,
                    declared = true,
                    scope = scopeLabel(entry.scope),
                )
                declaredRoots += node
                nodes.putIfAbsent(node.coordinate, node)
            }

            is PlatformDependency -> {
                // A BOM contributes no artifact — show it as a declared root so the user sees the
                // version source for their versionless dependencies.
                val node = UiDependencyNode(
                    coordinate = entry.bom.toString(),
                    group = entry.bom.group, name = entry.bom.name, version = entry.bom.version,
                    kind = UiDepKind.Platform, declared = true, scope = "platform",
                )
                declaredRoots += node
                nodes.putIfAbsent(node.coordinate, node)
            }

            is SdkDependency -> { /* the SDK is shown by the project tree, not as a managed dependency */
            }
        }

        return UiModuleDeps(
            moduleName = moduleName,
            buildSystem = buildSystem,
            acceptsAar = accepts,
            // Dedup by coordinate: two declared coordinates that share a group:name (e.g. two versions of the
            // same artifact) collapse to the SAME resolved node after conflict resolution, so the same node
            // would otherwise appear twice — which crashes the LazyColumn (duplicate item key). Keep the first.
            declared = declaredRoots.distinctBy { it.coordinate },
            nodes = nodes.values.toList(),
            conflicts = result?.conflicts?.map {
                UiVersionConflict(
                    it.coordinate, it.requested, it.chosen
                )
            }.orEmpty(),
            cycles = detectCycles(edges),
            unresolved = unresolved.toList(),
        )
    }

    /** Repository search (Maven Central), each hit pre-judged against [moduleName]'s AAR compatibility. */
    suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> {
        val module = ctx.modules().firstOrNull { it.name == moduleName }
        val accepts = module?.let { acceptsAar(it) } ?: true
        return depsResolver.search(query).map { hit ->
            val isAar = hit.packaging.equals("aar", ignoreCase = true)
            UiArtifactHit(
                coordinate = hit.coordinate.toString(),
                packaging = hit.packaging,
                compatible = !isAar || accepts,
                incompatibleReason = if (isAar && !accepts && module != null) aarReason(module) else null,
            )
        }
    }

    /**
     * Resolve [coordinate] and attach its full closure to [moduleName] as one [LibraryDependency] whose
     * library bundles every resolved jar/aar-classes root. Blocked when incompatible (e.g. an `.aar` on a
     * Java module). Re-indexes so completion/analysis pick up the new classpath.
     */
    suspend fun addDependency(
        moduleName: String,
        coordinate: String,
        scope: String,
        exclusions: List<String> = emptyList()
    ): UiAddResult {
        // The standalone "add" (Dependencies screen): owns the resolve-state flag; the resolution core is
        // shared with the deferred template-dependency loop ([startPendingDependencyResolution]).
        _depsState.value = DepsResolveState(
            resolving = true,
            message = "Resolving $coordinate…",
            log = listOf("Resolving $coordinate…")
        )
        return try {
            resolveAndAttach(
                moduleName,
                coordinate,
                scope,
                depsProgress(),
                exclusions = exclusions.mapNotNull(Exclusion::parse)
            )
        } finally {
            // resolveAndAttach → assembleModuleClasspath already stamped reasons; refresh the error state.
            _depsState.update { it.copy(resolving = false, unresolved = computeUnresolved()) }
        }
    }

    /** Resolve [coordinate] (with its transitive closure) and attach it to [moduleName]. Does NOT touch
     *  [depsState] — the caller owns the progress reporting (so a batch can show one continuous bar). When
     *  [finalize] is false, the save/analyzer-invalidate/reindex is skipped so a batch can do it once at the
     *  end (see [startPendingDependencyResolution]). */
    private suspend fun resolveAndAttach(
        moduleName: String,
        coordinate: String,
        scope: String,
        progress: ProgressReporter,
        finalize: Boolean = true,
        exclusions: List<Exclusion> = emptyList(),
    ): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false, "No module '$moduleName'."
        )
        val coord = parseInputCoordinate(coordinate) ?: return UiAddResult(
            false, "Invalid coordinate — expected group:name[:version]."
        )
        val versionless = coord.version.isBlank()
        val platforms = declaredPlatforms(module)
        if (versionless && platforms.isEmpty()) return UiAddResult(
            false,
            "$coordinate has no version — import a platform (BOM) first, or give an explicit version."
        )
        if (module.dependencies.any { it is LibraryDependency && it.library.name == coordinate }) return UiAddResult(
            false, "$coordinate is already a dependency of '$moduleName'."
        )

        val result = try {
            depsResolver.resolve(
                listOf(coord),
                currentRepositories(),
                conflictPolicy,
                progress,
                platforms = platforms,
                exclusions = if (exclusions.isEmpty()) emptyMap() else mapOf(coord to exclusions),
            )
        } catch (e: Exception) {
            return UiAddResult(false, "Resolution failed: ${e.message}")
        }

        val primary =
            result.resolved.firstOrNull { it.coordinate.group == coord.group && it.coordinate.name == coord.name }
                ?: return UiAddResult(
                    false,
                    if (versionless) "No imported platform provides a version for ${coord.group}:${coord.name}."
                    else "Couldn't find $coordinate in the configured repositories."
                )
        if (primary.kind == ArtifactKind.AAR && !acceptsAar(module)) return UiAddResult(
            false, "$coordinate is an Android library (.aar) — ${aarReason(module)}."
        )

        // Persist with the resolved, concrete coordinate — a versionless declaration is pinned to the
        // version the platform supplied at add time (a later BOM bump won't move it; re-add to update).
        val libraryName = primary.coordinate.toString()
        if (libraryName != coordinate && module.dependencies.any { it is LibraryDependency && it.library.name == libraryName }) return UiAddResult(
            false, "$libraryName is already a dependency of '$moduleName'."
        )

        // Record the declaration; its closure is assembled below as part of the WHOLE module graph (so the new
        // dependency's transitives are conflict-resolved against the existing ones, not unioned independently).
        val project =
            ctx.projectOf(module) ?: return UiAddResult(false, "No project owns '$moduleName'.")
        project.beginModification().apply {
            module(module.id).addDependency(
                LibraryDependency(
                    LibraryRef(libraryName), parseScope(scope), exclusions = exclusions
                )
            )
            commit()
        }
        val updated = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false, "Module '$moduleName' disappeared during resolution."
        )
        val asm = assembleModuleClasspath(updated, progress, finalize)
        // A fresh marker is now stale (the declared set changed) — let the next open re-verify cheaply.
        runCatching { java.nio.file.Files.deleteIfExists(reconcileMarker) }

        val transitiveCount = result.resolved.size - 1
        val suffix = if (transitiveCount > 0) " (+$transitiveCount transitive)" else ""
        // A non-empty `unresolved` means part of the graph failed to download (surfaced, not silently dropped):
        // the classpath is incomplete — warn so the user re-resolves instead of hitting "unresolved type" later.
        if (asm.unresolved.isNotEmpty()) {
            val missing =
                asm.unresolved.joinToString(", ") { "${it.group}:${it.name}:${it.version}" }
            return UiAddResult(
                true,
                "Added $libraryName$suffix, but ${asm.unresolved.size} artifact(s) failed to download and are missing from the classpath — re-resolve to complete it: $missing",
                result.resolved.size
            )
        }
        return UiAddResult(true, "Added $libraryName$suffix", result.resolved.size)
    }

    /**
     * Import a Maven BOM ([coordinate], `group:name:version`) as a platform of [moduleName] — the Gradle
     * `platform(...)` semantics. It contributes no artifact; it supplies versions to versionless
     * dependencies added afterwards. Verified resolvable before it's persisted.
     */
    suspend fun addPlatform(moduleName: String, coordinate: String): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false, "No module '$moduleName'."
        )
        val bom = parseCoordinate(coordinate) ?: return UiAddResult(
            false, "A platform BOM needs a version — expected group:name:version."
        )
        if (module.dependencies.any { it is PlatformDependency && it.bom == bom }) return UiAddResult(
            false, "$coordinate is already a platform of '$moduleName'."
        )

        _depsState.value = DepsResolveState(
            resolving = true,
            message = "Importing BOM $coordinate…",
            log = listOf("Importing BOM $coordinate…")
        )
        val result = try {
            depsResolver.resolve(
                emptyList(),
                currentRepositories(),
                conflictPolicy,
                depsProgress(),
                platforms = listOf(bom)
            )
        } catch (e: Exception) {
            return UiAddResult(false, "Couldn't import BOM: ${e.message}")
        } finally {
            _depsState.update { it.copy(resolving = false) }
        }
        if (bom in result.unresolved) return UiAddResult(
            false, "Couldn't find the BOM $coordinate in the configured repositories."
        )

        val project =
            ctx.projectOf(module) ?: return UiAddResult(false, "No project owns '$moduleName'.")
        project.beginModification().apply {
            module(module.id).addDependency(PlatformDependency(bom))
            commit()
        }
        ctx.store.save()
        return UiAddResult(true, "Imported platform $coordinate", 0)
    }

    /**
     * One-click Firebase setup: import the Firebase BoM as a platform, then add [artifacts] versionless
     * (the BoM supplies the version), and report whether a `google-services.json` is present (the user
     * downloads it from the Firebase console). No Gradle plugin is needed — the native build merges the
     * libraries' manifests (so `FirebaseInitProvider` lands) and processes `google-services.json` itself.
     * Idempotent: an already-imported BoM or already-added artifact is tolerated.
     */
    suspend fun addFirebase(
        moduleName: String,
        artifacts: List<String> = listOf("firebase-analytics"),
        bomVersion: String = "33.7.0",
    ): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false,
            "No module '$moduleName'."
        )
        if (!acceptsAar(module)) return UiAddResult(
            false, "Firebase libraries are Android (.aar) — ${aarReason(module)}."
        )

        // The Firebase BoM aligns every firebase-* artifact's version; versionless artifacts resolve against it.
        val bom = addPlatform(moduleName, "com.google.firebase:firebase-bom:$bomVersion")
        if (!bom.success && "already a platform" !in bom.message) return UiAddResult(
            false,
            "Couldn't import the Firebase BoM: ${bom.message}"
        )

        val (added, notes, failed) = addEach(
            moduleName, artifacts.map { if (':' in it) it else "com.google.firebase:$it" })
        val reminder = if (hasGoogleServicesJson(module)) emptyList()
        else listOf("add google-services.json to '$moduleName' (Firebase console → Project settings) to finish setup")
        return UiAddResult(!failed, "Firebase — " + (notes + reminder).joinToString("; "), added)
    }

    /** One-click Google Play Services: add each fully-qualified [coordinates] entry (e.g.
     *  `com.google.android.gms:play-services-maps:18.2.0`). GMS has no BoM, so versions are explicit. */
    suspend fun addGooglePlayServices(moduleName: String, coordinates: List<String>): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false,
            "No module '$moduleName'."
        )
        if (!acceptsAar(module)) return UiAddResult(
            false, "Play Services libraries are Android (.aar) — ${aarReason(module)}."
        )
        val (added, notes, failed) = addEach(moduleName, coordinates)
        return UiAddResult(!failed, "Play Services — " + notes.joinToString("; "), added)
    }

    /** Add a batch of coordinates, collecting per-item notes; tolerates already-present ones. */
    private suspend fun addEach(
        moduleName: String, coordinates: List<String>
    ): Triple<Int, List<String>, Boolean> {
        val notes = ArrayList<String>();
        var added = 0;
        var failed = false
        for (c in coordinates) {
            val r = addDependency(moduleName, c, "implementation")
            when {
                r.success -> {
                    added++; notes += "added $c"
                }

                "already a dependency" in r.message -> notes += "$c already present"
                else -> {
                    failed = true; notes += "failed $c: ${r.message}"
                }
            }
        }
        return Triple(added, notes, failed)
    }

    /** True if the module already ships a `google-services.json` (any variant-specific dir or the module root). */
    private fun hasGoogleServicesJson(module: Module): Boolean {
        val variant = AndroidVariants.defaultVariant(module) ?: return false
        val moduleDir =
            java.nio.file.Paths.get(module.outputDir.path).parent?.parent ?: return false
        return GoogleServices.findJson(moduleDir, variant) != null
    }

    /** Remove the declared library dependency or platform BOM [coordinate] from [moduleName] (false if absent). */
    fun removeDependency(moduleName: String, coordinate: String): Boolean {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return false
        val entry = module.dependencies.firstOrNull {
            (it is LibraryDependency && it.library.name == coordinate) || (it is PlatformDependency && it.bom.toString() == coordinate) || (it is ModuleDependency && it.target.value == coordinate)
        } ?: return false
        val project = ctx.projectOf(module) ?: return false
        project.beginModification().apply {
            module(module.id).removeDependency(entry)
            commit()
        }
        ctx.store.save()
        ctx.invalidateAnalyzers()
        ctx.resyncIndex()
        // The removed dep is no longer declared → drop any stale reason and refresh the error state.
        unresolvedReasons.remove(coordinate)
        publishDependencyHealth()
        // Removing a library changes the declared set, so the whole-graph partition must be recomputed: a
        // transitive the removed dep had claimed may still be needed by a remaining dep, and one only it pulled
        // in must now be pruned. Re-assemble in the background (cache-first → cheap).
        if (entry is LibraryDependency) {
            reconcileStarted.set(false)
            runCatching { java.nio.file.Files.deleteIfExists(reconcileMarker) }
            depsScope.launch {
                runCatching {
                    ctx.modules().firstOrNull { it.name == moduleName }
                        ?.let { assembleModuleClasspath(it, NoProgress, finalize = true) }
                }
                publishDependencyHealth()
            }
        }
        return true
    }

    /**
     * Replace the transitive exclusions on an already-declared library dependency [coordinate] of [moduleName],
     * then re-resolve the module so the closure reflects the change (a newly-excluded transitive is pruned; a
     * previously-excluded one comes back). A no-op (success) when the parsed set is unchanged.
     */
    suspend fun setExclusions(
        moduleName: String, coordinate: String, exclusions: List<String>
    ): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false,
            "No module '$moduleName'."
        )
        val entry = module.dependencies.filterIsInstance<LibraryDependency>()
            .firstOrNull { it.library.name == coordinate } ?: return UiAddResult(
            false, "$coordinate is not a library dependency of '$moduleName'."
        )
        val parsed = exclusions.mapNotNull(Exclusion::parse)
        if (parsed == entry.exclusions) return UiAddResult(true, "No change to exclusions.")
        val project =
            ctx.projectOf(module) ?: return UiAddResult(false, "No project owns '$moduleName'.")

        // Replace the declaration (remove + re-add carrying the new exclusions). Order among library deps
        // doesn't affect the resolved set — the whole graph is conflict-resolved newest-wins regardless.
        project.beginModification().apply {
            module(module.id).removeDependency(entry)
            module(module.id).addDependency(entry.copy(exclusions = parsed))
            commit()
        }
        _depsState.value = DepsResolveState(
            resolving = true,
            message = "Updating exclusions for $coordinate…",
            log = listOf("Updating exclusions for $coordinate…"),
        )
        return try {
            val updated = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
                false,
                "Module '$moduleName' disappeared."
            )
            val asm = assembleModuleClasspath(updated, depsProgress(), finalize = true)
            // The declared set changed → let the next open re-verify the persisted closure.
            runCatching { java.nio.file.Files.deleteIfExists(reconcileMarker) }
            if (asm.unresolved.isNotEmpty()) UiAddResult(
                true,
                "Exclusions updated, but ${asm.unresolved.size} artifact(s) failed to download — re-resolve to complete the classpath.",
                asm.resolvedCount,
            ) else UiAddResult(true, "Exclusions updated for $coordinate", asm.resolvedCount)
        } catch (e: Exception) {
            UiAddResult(false, "Re-resolution failed: ${e.message}")
        } finally {
            _depsState.update { it.copy(resolving = false, unresolved = computeUnresolved()) }
        }
    }

    // ---- local libraries (file-based jar/aar, no Maven coordinate) ----

    /** Where a picked local library is copied: a `libs/` folder under the module's directory. */
    fun localLibraryDropDir(moduleName: String): String? {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return null
        return ctx.moduleRoot(module)?.resolve("libs")?.toString()
    }

    /**
     * Existing `.jar`/`.aar` files under the project the module could attach — excluding build/platform dirs,
     * `.aar`s on a non-Android module, and ones already declared. Scans the project dir (so files imported
     * into any module/source root are offered).
     */
    fun localLibraryCandidates(moduleName: String): List<String> {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return emptyList()
        val accepts = acceptsAar(module)
        val declaredRoots = module.dependencies.filterIsInstance<LibraryDependency>()
            .mapNotNull { findLibrary(it.library.name) }
            .flatMap { it.classesRoots.map { r -> r.path } }.toSet()
        val base = ctx.moduleRoot(module)?.parent ?: ctx.store.rootPath
        if (!Files.isDirectory(base)) return emptyList()
        return runCatching {
            Files.walk(base).use { s ->
                s.filter { Files.isRegularFile(it) }
                    .map { it.toAbsolutePath().normalize().toString() }.filter { p ->
                        val low = p.lowercase()
                        (low.endsWith(".jar") || (accepts && low.endsWith(".aar"))) && !p.contains("/.platform/") && !p.contains(
                            "/build/"
                        ) && !p.contains(
                            "/.git/"
                        ) && p !in declaredRoots
                    }.sorted().distinct().collect(java.util.stream.Collectors.toList())
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Attach the local library at [path] (an absolute `.jar`/`.aar` already on disk) to [moduleName]. A jar is
     * registered as-is; an aar is exploded at add time into the Maven "exploded" form (classes.jar + res/ +
     * manifest siblings) so the editor reads its classes and the Android build routes its resources. No Maven
     * resolution — a local library has no transitive closure.
     */
    suspend fun addLocalLibrary(moduleName: String, path: String, scope: String): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false, "No module '$moduleName'."
        )
        val src = runCatching { Paths.get(path).toAbsolutePath().normalize() }.getOrNull()
            ?: return UiAddResult(false, "Invalid file path.")
        if (!Files.isRegularFile(src)) return UiAddResult(false, "File not found: ${src.fileName}.")
        val low = src.toString().lowercase()
        val isAar = low.endsWith(".aar")
        if (!isAar && !low.endsWith(".jar")) return UiAddResult(
            false, "Only .jar and .aar files can be added as a local library."
        )
        if (isAar && !acceptsAar(module)) return UiAddResult(
            false, "${src.fileName} is an Android library (.aar) — ${aarReason(module)}."
        )

        val libName = src.fileName.toString()
        if (module.dependencies.any { it is LibraryDependency && it.library.name == libName }) return UiAddResult(
            false, "$libName is already a dependency of '$moduleName'."
        )
        val project =
            ctx.projectOf(module) ?: return UiAddResult(false, "No project owns '$moduleName'.")

        val registered = runCatching {
            ctx.store.workspace.libraryTable.create(libName).apply {
                if (isAar) {
                    kind = LibraryKind.AAR
                    val into = ctx.store.rootPath.resolve(".platform/libs")
                        .resolve(libName.substringBeforeLast('.'))
                    AarExtractor.explode(
                        src, into
                    ).classesJars.forEach { addClassesRoot(ctx.store.vfs.fileFor(it)) }
                } else {
                    kind = LibraryKind.JAR
                    addClassesRoot(ctx.store.vfs.fileFor(src))
                }
                commit()
            }
        }
        registered.exceptionOrNull()
            ?.let { return UiAddResult(false, "Couldn't read ${src.fileName}: ${it.message}") }

        project.beginModification().apply {
            module(module.id).addDependency(
                LibraryDependency(
                    LibraryRef(libName), parseScope(scope)
                )
            )
            commit()
        }
        ctx.store.save()
        ctx.invalidateAnalyzers()
        ctx.resyncIndex()
        return UiAddResult(true, "Added $libName", 1)
    }

    // ---- repositories (where libraries resolve from) ----

    /** User-added repositories, persisted one `name<TAB>url` per line; the built-ins are always prepended. */
    private val repositoriesFile: Path get() = ctx.store.rootPath.resolve(".platform/repositories.txt")

    private fun userRepositories(): List<Repository> =
        runCatching { repositoriesFile.readText() }.getOrNull()?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[1].isNotBlank()) Repository(
                    parts[0].trim(), parts[1].trim()
                ) else null
            }?.toList().orEmpty()

    /** The repository list every resolve uses: the built-ins (Maven Central, Google) plus the user's. */
    private fun currentRepositories(): List<Repository> = DEFAULT_REPOSITORIES + userRepositories()

    /** Built-in repos (locked) + user-added repos (removable), for the Repositories manager. */
    fun repositories(): List<UiRepository> = DEFAULT_REPOSITORIES.map {
        UiRepository(
            it.name, it.url, builtin = true
        )
    } + userRepositories().map { UiRepository(it.name, it.url, builtin = false) }

    /** Add a custom repository. Rejects a blank/non-http URL or one already provided (built-in or user). */
    fun addRepository(name: String, url: String): Boolean {
        val u = url.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        if (currentRepositories().any { it.url.trimEnd('/') == u.trimEnd('/') }) return false
        val next = userRepositories() + Repository(name.trim().ifEmpty { u }, u)
        return writeRepositories(next)
    }

    /** Remove a user-added repository by URL (built-ins can't be removed). */
    fun removeRepository(url: String): Boolean {
        val u = url.trim().trimEnd('/')
        val current = userRepositories()
        val remaining = current.filterNot { it.url.trimEnd('/') == u }
        if (remaining.size == current.size) return false
        return writeRepositories(remaining)
    }

    private fun writeRepositories(repos: List<Repository>): Boolean = runCatching {
        Files.createDirectories(repositoriesFile.parent)
        repositoriesFile.writeText(repos.joinToString("") { "${it.name}\t${it.url}\n" })
    }.isSuccess


    // ---- module-on-module dependencies ----

    /** Modules [moduleName] may depend on: same project, not itself, not already a dep, and no cycle. */
    fun moduleDependencyTargets(moduleName: String): List<String> {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return emptyList()
        val project = ctx.projectOf(module) ?: return emptyList()
        val existing =
            module.dependencies.filterIsInstance<ModuleDependency>().map { it.target.value }.toSet()
        return project.modules.asSequence()
            .filter { it.id != module.id && it.id.value !in existing }.filterNot {
                dependsOnTransitively(
                    it, module.id, project
                )
            } // it depends on us → would cycle
            .map { it.name }.toList()
    }

    /** True if [from] depends on [targetId] directly or transitively (module-graph walk, cycle-guarded). */
    private fun dependsOnTransitively(from: Module, targetId: ModuleId, project: Project): Boolean {
        val byId = project.modules.associateBy { it.id }
        val seen = HashSet<ModuleId>()
        val stack = ArrayDeque<Module>().apply { add(from) }
        while (stack.isNotEmpty()) {
            val m = stack.removeLast()
            if (!seen.add(m.id)) continue
            for (dep in m.dependencies.filterIsInstance<ModuleDependency>()) {
                if (dep.target == targetId) return true
                byId[dep.target]?.let { stack.add(it) }
            }
        }
        return false
    }

    /** Add a module-on-module dependency from [moduleName] onto [targetModule]. An `api` scope is exported
     *  (Gradle semantics). Blocked on self/cycle/dup. */
    fun addModuleDependency(moduleName: String, targetModule: String, scope: String): UiAddResult {
        val module = ctx.modules().firstOrNull { it.name == moduleName } ?: return UiAddResult(
            false, "No module '$moduleName'."
        )
        if (targetModule == moduleName) return UiAddResult(
            false, "A module can't depend on itself."
        )
        val project =
            ctx.projectOf(module) ?: return UiAddResult(false, "No project owns '$moduleName'.")
        val target = project.modules.firstOrNull { it.name == targetModule } ?: return UiAddResult(
            false, "No module '$targetModule' in this project."
        )
        if (module.dependencies.any { it is ModuleDependency && it.target == target.id }) return UiAddResult(
            false, "'$moduleName' already depends on '$targetModule'."
        )
        if (dependsOnTransitively(target, module.id, project)) return UiAddResult(
            false, "That would create a cycle ('$targetModule' already depends on '$moduleName')."
        )
        val resolvedScope = parseScope(scope)
        try {
            project.beginModification().apply {
                module(module.id).addDependency(
                    ModuleDependency(
                        target.id, resolvedScope, exported = resolvedScope == DependencyScope.API
                    )
                )
                commit()
            }
        } catch (e: Exception) {
            return UiAddResult(false, "Couldn't add: ${e.message}")
        }
        ctx.store.save()
        ctx.invalidateAnalyzers()
        ctx.resyncIndex()
        return UiAddResult(true, "Added module dependency on '$targetModule'", 0)
    }

    /** The dependency version-conflict policy every resolve in this project uses (loaded per project). */
    @Volatile
    var conflictPolicy: ConflictPolicy = loadConflictPolicy()
        private set

    fun setConflictPolicy(policy: ConflictPolicy) {
        if (policy == conflictPolicy) return
        conflictPolicy = policy
        ctx.setProjectPref(CONFLICT_POLICY_PREF, policy.name)
    }

    private fun loadConflictPolicy(): ConflictPolicy =
        ctx.projectPref(CONFLICT_POLICY_PREF)?.let { runCatching { ConflictPolicy.valueOf(it) }.getOrNull() }
            ?: ConflictPolicy.NEWEST

    /** Declare a freshly-created project's template dependencies and queue them for background resolution
     *  (called by the project factory right after the engine is built). */
    fun setPendingDependencies(deps: List<dev.ide.model.template.TemplateDependency>) {
        declareTemplateDependencies(deps)
        pendingDeps = deps
    }

    /** Tarjan-lite cycle detection over the resolved `dependsOn` [edges] — surfaces metadata cycles to the UI. */
    private fun detectCycles(edges: Map<String, List<String>>): List<List<String>> {
        val cycles = ArrayList<List<String>>()
        val done = HashSet<String>()
        val onStack = HashSet<String>()
        val stack = ArrayList<String>()
        fun dfs(node: String) {
            if (node in onStack) {
                val idx = stack.indexOf(node)
                if (idx >= 0) cycles += stack.subList(idx, stack.size).toList() + node
                return
            }
            if (node in done) return
            onStack += node; stack += node
            edges[node].orEmpty().forEach(::dfs)
            stack.removeAt(stack.lastIndex); onStack -= node; done += node
        }
        edges.keys.toList().forEach(::dfs)
        return cycles.distinctBy { it.toSet() }
    }

    override fun dispose() {
        depsScope.cancel()
    }
}
