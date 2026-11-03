package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.IdeServices
import dev.ide.core.LoweredComposePreview
import dev.ide.build.RunCapture
import dev.ide.lang.hints.InlayHintKind
import dev.ide.model.impl.rootPath
import dev.ide.ui.backend.LearnService
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiExerciseResult
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiLearnAccount
import dev.ide.ui.backend.UiLearnBadge
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnPublicProfile
import dev.ide.ui.backend.UiLearnPath
import dev.ide.ui.backend.UiLearnProgress
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiLesson
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.backend.UiLessonSummary
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiResumePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * [LearnService] for the home screen's Learn tab. Serves the bundled [LearnContent] catalog, persists local
 * progress through the [ProjectManager][dev.ide.core.project.ProjectManager] preferences, and auto-checks interactive
 * exercises by compiling + running the learner's code in a hidden scratch project ([ProjectManager.scratch]
 * → [IdeServices.runAndCapture]) and comparing its output to the step's [ExerciseCheck]. Exercise answers
 * never leave the backend; only the pass/fail result + captured output are returned. The interface is exactly
 * what a remote, submission-backed lesson catalog will later implement, so the UI does not change.
 */
internal class LearnBackend(
    private val ctx: BackendContext,
    private val remote: dev.ide.store.LearnRemoteService = dev.ide.store.LearnRemoteService.Unsupported,
) : LearnService {

    private val log = dev.ide.platform.log.Log.logger("learn.compose")

    /** Bundled vs downloaded catalog; serves the newer. The cache sits beside the other app-wide data. */
    private val catalogStore = LearnCatalogStore(
        bundled = { LearnContent.catalog },
        remote = remote,
        cacheFile = { ctx.manager?.sharedRoot?.resolve("learn")?.resolve("catalog.json") },
        log = { msg, t -> if (t != null) log.warn(msg, t) else log.info(msg) },
    )

    private val tracks: List<LearnTrackDef> get() = catalogStore.catalog().tracks

    /** Discussions and shared solutions; the same Supabase client as the catalog when it offers them. */
    private val community = LearnCommunityOps(
        community = remote as? dev.ide.store.LearnCommunityService ?: dev.ide.store.LearnCommunityService.Unsupported,
        lessonTitle = { id -> findLesson(id)?.second?.title },
        stepTitle = { lessonId, stepId -> stepId?.let { findStep(lessonId, it)?.title } },
    )

    /** Background work that must not hold up the caller (sharing a solution after a pass). */
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** Device progress plus its sync with the signed-in account. */
    private val progressSync = LearnProgressSync(
        remote = remote,
        pref = ::pref,
        setPref = ::setPref,
        catalog = { catalogStore.catalog() },
    )

    /** Scratch keys whose extra dependencies (e.g. coroutines) have been resolved this session. */
    private val depsEnsured = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Serializes [ensureCompose] so the live preview's per-render `scratchFor` calls can't launch several
     *  concurrent resolutions of the same coordinates before the first sets [depsEnsured]. */
    private val composeDepsMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Serializes lesson Compose lowering. Every lesson preview goes through ONE scratch file: finding the
     * `@Preview` and lowering it are two calls that each pass the snippet as that file's overlay, so two
     * previews on one screen lowering at once interleaved, and one of them lowered the other's code
     * ("lowered with no diagnostics" and a blank card, alternating between the two on each visit).
     */
    private val composeLowerMutex = kotlinx.coroutines.sync.Mutex()

    override fun learnAvailable(): Boolean = tracks.isNotEmpty()

    override suspend fun catalog(): UiLearnCatalog {
        val c = catalogStore.catalog()
        return UiLearnCatalog(
            tracks = c.tracks.map { it.toUiTrack() },
            paths = c.paths.map { p ->
                UiLearnPath(p.id, p.title, p.subtitle, p.iconId, p.accentColor, p.trackIds)
            },
            version = c.version,
        )
    }

    override suspend fun refreshCatalog(): Boolean =
        withContext(Dispatchers.IO) { runCatching { catalogStore.refresh() }.getOrDefault(false) }

    override suspend fun lesson(id: String): UiLesson? {
        val (track, lesson) = findLesson(id) ?: return null
        return UiLesson(
            lesson.id, track.id, lesson.title, lesson.summary, track.language,
            lesson.steps.map { it.toUiStep() }, lesson.kind,
        )
    }

    override suspend fun complete(language: String, code: String, offset: Int): UiCompletionResult {
        if (ctx.manager == null) return UiCompletionResult(emptyList(), offset, offset)
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor(language) ?: return@runCatching UiCompletionResult(emptyList(), offset, offset)
                val mainPath = ensureMain(services, language)
                val off = offset.coerceIn(0, code.length)
                val res = services.complete(mainPath, code, off).toUi()
                val start = identifierStart(code, off)
                // Two corrections for the lesson editor:
                //  1) Re-anchor the replacement range to the identifier token before the caret — some paths
                //     return a zero-width range, which would make the editor compute an EMPTY prefix and show
                //     the whole unfiltered set (typing "System" showed "Security").
                //  2) Force isIncomplete = true so the editor RE-QUERIES on every keystroke instead of only
                //     narrowing its cached (possibly truncated) set locally. Without this, if "System" fell
                //     outside a short prefix's truncated result, it never reappeared and no re-query fired.
                //     The scratch project is tiny, so per-keystroke re-query is cheap; local filtering still
                //     gives instant feedback between requests.
                res.copy(replaceStart = start, replaceEnd = off, isIncomplete = true)
            }.getOrElse { UiCompletionResult(emptyList(), offset, offset) }
        }
    }

    /** Start of the identifier token ending at [offset] (letters/digits/`_`/`$`) — mirrors the editor's own
     *  token-boundary logic so the completion's replacement range matches what the popup narrows by. */
    private fun identifierStart(code: String, offset: Int): Int {
        var i = offset.coerceIn(0, code.length)
        while (i > 0 && code[i - 1].let { it.isLetterOrDigit() || it == '_' || it == '$' }) i--
        return i
    }

    override suspend fun analyze(language: String, code: String): List<UiDiagnostic> {
        if (ctx.manager == null) return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor(language) ?: return@runCatching emptyList()
                val mainPath = ensureMain(services, language)
                services.analyzeDiagnostics(mainPath, code).toUiDiagnostics(code)
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun hints(language: String, code: String, startOffset: Int, endOffset: Int): List<UiInlayHint> {
        if (ctx.manager == null) return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor(language) ?: return@runCatching emptyList()
                val mainPath = ensureMain(services, language)
                val start = startOffset.coerceIn(0, code.length)
                val end = endOffset.coerceIn(start, code.length)
                services.inlayHints(mainPath, code, start, end).map { h ->
                    UiInlayHint(
                        offset = h.offset,
                        // Drop the per-part navigation symbol: the lesson editor is a standalone scratch buffer
                        // with no project to jump into, so a clickable type run would go nowhere.
                        parts = h.parts.map { UiInlayPart(it.text) },
                        kind = when (h.kind) {
                            InlayHintKind.TYPE -> UiInlayKind.Type
                            InlayHintKind.PARAMETER -> UiInlayKind.Parameter
                            InlayHintKind.CHAINING -> UiInlayKind.Chaining
                            InlayHintKind.OTHER -> UiInlayKind.Other
                        },
                        tooltip = h.tooltip,
                        paddingLeft = h.paddingLeft,
                        paddingRight = h.paddingRight,
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun folds(language: String, code: String): List<UiFoldRegion> {
        if (ctx.manager == null) return emptyList()
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor(language) ?: return@runCatching emptyList()
                val mainPath = ensureMain(services, language)
                services.codeFolds(mainPath, code).map { f ->
                    UiFoldRegion(
                        startOffset = f.range.start,
                        endOffset = f.range.end,
                        placeholder = f.placeholder,
                        kind = f.kind.id,
                        collapsedByDefault = f.collapsedByDefault,
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun prepare(language: String): Boolean {
        if (ctx.manager == null) return true
        // Compose resolves a much larger dependency graph the first time (androidx.compose.*), so give it a
        // longer cap than the plain-Kotlin/coroutines scratch.
        val cap = if (language == "kotlin-compose") COMPOSE_PREPARE_WAIT_MS else PREPARE_WAIT_MS
        return withContext(Dispatchers.Default) {
            // Bound the WHOLE prep — creating the scratch, resolving the dependencies the first time, and waiting
            // for the index — so the "Preparing" gate can't hang. If it times out the gate opens anyway; the
            // editor re-analyzes when [indexing] settles and [scratchFor] retries the dependency next time.
            withTimeoutOrNull(cap) {
                runCatching {
                    val services = scratchFor(language)
                    if (services != null) {
                        ensureMain(services, language)
                        services.indexStatus.first { !it.building }
                    }
                }
            }
            true
        }
    }

    override suspend fun indexing(language: String): Boolean {
        if (ctx.manager == null) return false
        return withContext(Dispatchers.Default) {
            runCatching { (scratchFor(language) ?: return@runCatching false).indexStatus.value.building }.getOrDefault(false)
        }
    }

    override suspend fun check(lessonId: String, stepId: String, code: String): UiExerciseResult {
        val result = checkExercise(lessonId, stepId, code)
        if (result.passed) shareSolutionLater(lessonId, stepId, code)
        return result
    }

    /** A pass is shared with other learners in the background, when signed in and not opted out. */
    private fun shareSolutionLater(lessonId: String, stepId: String, code: String) {
        val step = findStep(lessonId, stepId) as? LearnStepDef.Interactive ?: return
        if (step.check.ide.isNotEmpty()) return // a tour step's "solution" is what the learner did, not code
        if (!community.available() || remote.accountId() == null || !shareSolutions()) return
        backgroundScope.launch { community.submitSolution(lessonId, stepId, code, step.language) }
    }

    private suspend fun checkExercise(lessonId: String, stepId: String, code: String): UiExerciseResult {
        val step = findStep(lessonId, stepId) as? LearnStepDef.Interactive
            ?: return UiExerciseResult(passed = false, compiled = false, message = "This step has no exercise to check.")
        // A layout exercise is checked against its structure and a render; nothing compiles or runs, so it
        // needs no scratch project (and works with no project manager at all).
        step.check.layout?.let { layout ->
            return withContext(Dispatchers.Default) { checkLayout(code, layout, ::renderProblems) }
        }
        if (step.check.ide.isNotEmpty()) {
            val services = activeWorkspaceFor(step.language)
                ?: return failure("Open this lesson from Learn so its practice project is the one in the editor.")
            return withContext(Dispatchers.IO) { checkIde(step.check.ide, probeFor(services, step.language)) }
        }
        if (ctx.manager == null) {
            return UiExerciseResult(passed = false, compiled = false, message = "Running exercises isn't available in this build.")
        }
        return withContext(Dispatchers.Default) {
            // In the editor the learner's own workspace is the active project: run there, so what Check runs
            // is exactly what they see (and no second engine is started). Elsewhere, the hidden scratch.
            val workspace = activeWorkspaceFor(step.language)
            val services = workspace ?: try {
                scratchFor(step.language) ?: return@withContext failure("The exercise runner isn't available.")
            } catch (e: Throwable) {
                return@withContext failure("Couldn't prepare the exercise runner: ${reason(e)}")
            }
            runCatching {
                if (workspace != null) workspace.save(workspaceFile(workspace, step.language), code)
                else writeMain(services, step.language, code)
            }.exceptionOrNull()?.let {
                return@withContext failure("Couldn't save your code: ${reason(it)}")
            }
            if (step.check.cases.isNotEmpty()) return@withContext runCases(services, step.check, code)
            val cap = try {
                services.runAndCapture("app")
            } catch (e: Throwable) {
                return@withContext failure("The exercise runner failed: ${reason(e)}")
            }
            interpret(cap, step.check, code)
        }
    }

    /**
     * Run a multi-case exercise: the same program once per [ExerciseCheck.cases], each fed its own stdin.
     * Stops at the first case that fails and shows its input, so the learner sees exactly what broke.
     */
    private suspend fun runCases(services: IdeServices, check: ExerciseCheck, code: String): UiExerciseResult {
        var firstOutput = ""
        val total = check.cases.size
        for ((i, case) in check.cases.withIndex()) {
            val cap = try {
                services.runAndCapture("app", stdin = case.stdin)
            } catch (e: Throwable) {
                return failure("The exercise runner failed: ${reason(e)}")
            }
            if (!cap.compiled) return interpret(cap, check, code)
            if (i == 0) firstOutput = cap.stdout
            val verdict = caseVerdict(cap.stdout, case, check.caseSensitive)
            if (verdict != null) {
                val input = case.stdin.trimEnd().ifBlank { "(no input)" }
                return UiExerciseResult(
                    passed = false,
                    compiled = true,
                    output = cap.stdout,
                    message = "Test ${i + 1} of $total failed.\nInput:\n$input\n$verdict",
                    diagnostics = cap.diagnostics,
                )
            }
        }
        val missing = missingConstructs(code, check.requireSource)
        if (missing.isNotEmpty()) {
            return UiExerciseResult(
                passed = false, compiled = true, output = firstOutput,
                message = "All $total tests pass, but the exercise wants you to actually use " +
                    missing.joinToString(", ") { "`$it`" } + ".",
            )
        }
        return UiExerciseResult(passed = true, compiled = true, output = firstOutput, message = "All $total tests passed. Nicely done!")
    }

    /** Null when [stdout] satisfies [case], else what was expected. */
    private fun caseVerdict(stdout: String, case: ExerciseCase, caseSensitive: Boolean): String? {
        val actual = normalize(stdout, caseSensitive)
        return when {
            case.expectedOutput != null ->
                if (actual == normalize(case.expectedOutput, caseSensitive)) null
                else "Expected output:\n${case.expectedOutput.trimEnd()}"
            case.mustContain.isNotEmpty() ->
                if (case.mustContain.all { actual.contains(normalize(it, caseSensitive)) }) null
                else "Your output should include: ${case.mustContain.joinToString(", ")}"
            else -> null
        }
    }

    /** The owned engine's problems for [xml], or null when it could not render at all. */
    private fun renderProblems(xml: String): List<String>? =
        dev.ide.core.renderStandaloneLayout(xml, LAYOUT_CHECK_REQUEST)?.problems?.map { "${it.tag}: ${it.message}" }

    // ---- editor workspaces ----

    /** Where a language's lessons happen in the full editor: a real project and the one file a step edits. */
    private data class WorkspaceSpec(
        val key: String,
        val templateId: String,
        val args: Map<String, String>,
        val srcDir: String,
        val fileName: String,
    )

    private fun workspaceSpec(language: String): WorkspaceSpec = when (language) {
        // Hands-on CodeAssist lessons: their own practice projects, separate from the coding lessons', so a
        // tour's files (a Gson import, a renamed function) never leak into an exercise.
        "ide-kotlin" -> WorkspaceSpec("tour-kotlin", "kotlin-console", emptyMap(), "app/src/main/kotlin", "Main.kt")
        "ide-android" -> WorkspaceSpec(
            "tour-android", "android-app",
            mapOf("packageName" to "dev.ide.learn.tour", "language" to "kotlin", "minSdk" to "26"),
            "app/src/main/res/layout", "tour.xml",
        )
        "xml-layout" -> WorkspaceSpec(
            "android", "android-app",
            mapOf("packageName" to "dev.ide.learn.app", "language" to "kotlin", "minSdk" to "26"),
            "app/src/main/res/layout", "lesson.xml",
        )
        "java" -> WorkspaceSpec("java", "java-console", emptyMap(), "app/src/main/java", "Main.java")
        "kotlin-coroutines" -> WorkspaceSpec("coroutines", "kotlin-console", emptyMap(), "app/src/main/kotlin", "Main.kt")
        else -> WorkspaceSpec("kotlin", "kotlin-console", emptyMap(), "app/src/main/kotlin", "Main.kt")
    }

    private fun Path.norm(): Path = toAbsolutePath().normalize()

    /** The active engine when it is the workspace for [language], else null. */
    private fun activeWorkspaceFor(language: String): IdeServices? {
        val mgr = ctx.manager ?: return null
        val active = ctx.servicesOrNull ?: return null
        return active.takeIf { it.workspaceRoot.norm() == mgr.learnWorkspaceDir(workspaceSpec(language).key).norm() }
    }

    private fun workspaceFile(services: IdeServices, language: String): Path {
        val spec = workspaceSpec(language)
        return services.workspaceRoot.resolve(spec.srcDir).resolve(spec.fileName)
    }

    /** The language a lesson's exercises are written in, or null when it has none (it stays in the player). */
    private fun exerciseLanguage(lesson: LearnLessonDef): String? =
        lesson.steps.filterIsInstance<LearnStepDef.Interactive>().firstOrNull()?.language

    override suspend fun openWorkspace(lessonId: String): Boolean = withContext(Dispatchers.IO) {
        val mgr = ctx.manager ?: return@withContext false
        val lesson = findLesson(lessonId)?.second ?: return@withContext false
        val language = exerciseLanguage(lesson) ?: return@withContext false
        val spec = workspaceSpec(language)
        runCatching {
            val services = activeWorkspaceFor(language) ?: mgr.learnWorkspace(spec.key, spec.templateId, spec.args) { created ->
                // A console template ships a sample `Main` in a package. Lessons write a default-package Main,
                // and two mains would make the run pick one at random, so the sample goes.
                if (spec.fileName != "lesson.xml") clearSources(created.workspaceRoot.resolve(spec.srcDir))
            }.also { ctx.swapEngine(it) }
            if (language == "kotlin-coroutines" && "ws-coroutines" !in depsEnsured) {
                val ok = runCatching { services.dependencies.addDependency("app", COROUTINES_COORD, "implementation") }
                    .map { it.success || "already a dependency" in it.message }.getOrDefault(false)
                if (ok) depsEnsured.add("ws-coroutines")
            }
            true
        }.getOrElse { log.warn("Couldn't open the lesson workspace for $lessonId", it); false }
    }

    override suspend fun prepareStep(lessonId: String, stepId: String, content: String?): String? =
        withContext(Dispatchers.IO) {
            val step = findStep(lessonId, stepId) as? LearnStepDef.Interactive ?: return@withContext null
            val services = activeWorkspaceFor(step.language) ?: return@withContext null
            if (step.language.startsWith("ide-")) return@withContext prepareTourStep(services, lessonId, step)
            val path = workspaceFile(services, step.language)
            // Re-entering the same step (Back then Next, or reopening the lesson) keeps what the learner wrote;
            // a new step, or an explicit [content] (Copy solution), replaces it.
            val markerKey = KEY_WORKSPACE_STEP + workspaceSpec(step.language).key
            val marker = "$lessonId/$stepId"
            val replace = content != null || pref(markerKey) != marker || !Files.exists(path)
            if (replace) {
                val text = (content ?: step.starterCode.trimIndent()).trimEnd() + "\n"
                runCatching { services.save(path, text) }.onFailure { return@withContext null }
                setPref(markerKey, marker)
            }
            path.toString()
        }

    /**
     * A hands-on step: write its seed files the first time it is opened (re-entering keeps the learner's
     * changes), note when it started and which run was current, and return the file the editor opens.
     */
    private fun prepareTourStep(services: IdeServices, lessonId: String, step: LearnStepDef.Interactive): String? {
        val key = workspaceSpec(step.language).key
        val root = services.workspaceRoot
        val marker = "$lessonId/${step.id}"
        if (pref(KEY_WORKSPACE_STEP + key) != marker) {
            for ((rel, text) in step.files) {
                runCatching { services.save(root.resolve(rel), text.trimIndent().trimEnd() + "\n") }
                    .onFailure { log.warn("Couldn't seed $rel for ${step.id}", it) }
            }
            setPref(KEY_WORKSPACE_STEP + key, marker)
            setPref(KEY_WORKSPACE_START + key, System.currentTimeMillis().toString())
        }
        // ConcurrentHashMap takes no nulls: -1 stands for "no run yet".
        runBaseline[key] = services.build.runConsole.value?.id ?: NO_RUN
        return step.openFile?.let { root.resolve(it).toString() }
    }

    /** Per tour workspace: the run console session that was current when the step was entered. */
    private val runBaseline = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** [WorkspaceProbe] over the active tour workspace. */
    private fun probeFor(services: IdeServices, language: String): WorkspaceProbe {
        val key = workspaceSpec(language).key
        val root = services.workspaceRoot
        return object : WorkspaceProbe {
            override val stepStartedMillis: Long = pref(KEY_WORKSPACE_START + key)?.toLongOrNull() ?: 0L

            override fun read(path: String): String? =
                root.resolve(path).takeIf { Files.isRegularFile(it) }?.let { String(Files.readAllBytes(it), Charsets.UTF_8) }

            override suspend fun compileErrors(): List<String>? {
                val cap = runCatching { services.runAndCapture("app") }.getOrElse { return listOf(reason(it)) }
                return if (cap.compiled) null else cap.diagnostics.ifEmpty { listOf("The project did not compile.") }
            }

            override fun runOutputSinceStep(): String? {
                val rc = services.build.runConsole.value ?: return null
                if (rc.id == runBaseline[key]) return null
                return rc.transcript.joinToString("") { it.text }
            }

            override fun dependencies(): List<String> = services.modules().flatMap { m ->
                m.dependencies.filterIsInstance<dev.ide.model.LibraryDependency>().map { it.library.name }
            }

            override fun keystoreCount(): Int = runCatching { services.keystoreRegistry.all().size }.getOrDefault(0)

            override fun newestApkMillis(): Long = runCatching {
                Files.walk(root).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".apk") && it.toString().contains("/build/") }
                        .mapToLong { Files.getLastModifiedTime(it).toMillis() }
                        .max().orElse(0L)
                }
            }.getOrDefault(0L)

            override fun gitReflog(): String? =
                root.resolve(".git/logs/HEAD").takeIf { Files.isRegularFile(it) }?.let { String(Files.readAllBytes(it), Charsets.UTF_8) }
        }
    }

    private fun clearSources(dir: Path) {
        if (!Files.isDirectory(dir)) return
        Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java")) }
                .forEach { runCatching { Files.delete(it) } }
        }
    }

    // ---- scratch project selection (per language, coroutine-aware) ----

    /** The scratch engine for [language], creating/reusing it. Coroutine lessons (`"kotlin-coroutines"`) use a
     *  SEPARATE scratch (so basic Kotlin lessons never need the network) and get kotlinx-coroutines resolved
     *  onto their classpath so the exercise compiles + runs. Returns null when no project manager is available. */
    private suspend fun scratchFor(language: String): IdeServices? {
        val manager = ctx.manager ?: return null
        return when (language) {
            "kotlin-coroutines" -> manager.scratch("kotlin-coroutines", "kotlin-console").also { ensureCoroutines(it) }
            // Compose needs an ANDROID module: the `androidx.compose.*` symbols ship as AARs, which the dependency
            // resolver only attaches to an Android module (a JVM `kotlin-console` rejects them, and the JetBrains
            // `-desktop` jars don't populate the top-level-function index the call resolver uses). An android-lib
            // resolves `Text`/`Column`/… exactly the way the editor's Compose preview does on a real project.
            // NOTE: a distinct scratch key ("compose-android") so an existing on-disk `kotlin-console` scratch
            // from an earlier build isn't reopened as the wrong (JVM) module type — this makes a fresh dir.
            "kotlin-compose" -> manager.scratch(
                "compose-android", "android-library",
                mapOf("packageName" to "dev.ide.learn.compose", "language" to "kotlin", "minSdk" to "26"),
            ).also { ensureCompose(it) }
            "kotlin" -> manager.scratch("kotlin-console")
            else -> manager.scratch("java-console")
        }
    }

    /** Resolve + attach kotlinx-coroutines-core to the coroutines scratch (once per session; the resolver
     *  disk-caches it, so it's offline after the first success). Best-effort: on failure the exercise's
     *  compile error surfaces and we retry next time. */
    private suspend fun ensureCoroutines(services: IdeServices) {
        if ("kotlin-coroutines" in depsEnsured) return
        val ok = runCatching { services.dependencies.addDependency("app", COROUTINES_COORD, "implementation").success }
            .getOrDefault(false)
        if (ok) depsEnsured.add("kotlin-coroutines")
    }

    /** Resolve + attach the `androidx.compose.*` AARs to the Compose scratch (ONCE per session; the resolver
     *  disk-caches them, so it's offline after the first success). Lowering a `@Composable` needs compose on the
     *  classpath so `Text`/`Column`/`Button` resolve as library composables; the render then dispatches them
     *  against the launcher's bundled Compose. Attaches only because the scratch is an ANDROID module
     *  (`android-library`, module `"lib"`) — a plain-JVM module rejects AARs. Serialized + idempotent (mutex +
     *  [depsEnsured] guard) so the live preview's per-render `scratchFor` doesn't re-resolve on every keystroke.
     *  Best-effort: on failure it retries next session. */
    private suspend fun ensureCompose(services: IdeServices) {
        if ("kotlin-compose" in depsEnsured) return
        composeDepsMutex.withLock {
            if ("kotlin-compose" in depsEnsured) return@withLock
            val ok = COMPOSE_COORDS.all { coord ->
                runCatching {
                    // The Compose scratch's module is "lib" (the android-library template).
                    val r = services.dependencies.addDependency("lib", coord, "implementation")
                    // "already a dependency" is success too (a coordinate pulled transitively by an earlier one).
                    r.success || "already a dependency" in r.message
                }.getOrElse { log.warn("ensureCompose: $coord failed", it); false }
            }
            if (ok) depsEnsured.add("kotlin-compose")
        }
    }

    /** Lower the first `@Preview @Composable` in a self-contained lesson [code] to a renderable interpreter
     *  program, against the hidden Compose scratch (which carries `androidx.compose.*`). The platform Compose
     *  preview host composes the result; null when there's no preview, it isn't interpretable, or no scratch. */
    suspend fun lowerCompose(code: String): LoweredComposePreview? {
        if (ctx.manager == null) return null
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor("kotlin-compose") ?: return@runCatching null
                val path = mainPathOf(services, "kotlin-compose")
                ensureMain(services, "kotlin-compose")
                composeLowerMutex.withLock {
                val preview = services.composePreviews(path, code).firstOrNull() ?: return@withLock null
                // The lesson host renders with tolerateGaps = false, so a program whose reachable helper (e.g.
                // `Counter`) didn't resolve compose yet would throw "has unsupported nodes" mid-render. The
                // lowering gate refuses that for every caller, which keeps the host in its retry loop until the
                // scratch's compose attaches.
                services.lowerComposePreview(path, code, preview.functionName, preview.arity)
                }
            }.getOrElse { log.warn("lowerCompose failed", it); null }
        }
    }

    /** Whether the Compose scratch can resolve library composables yet: the one-time `androidx.compose.*`
     *  download + attach may still be in flight on first run, during which `Text`/`Column`/`remember` resolve
     *  to 0 candidates. The preview host polls this to show a "Preparing" state and retry instead of latching
     *  the first failed lower into a permanent error. False (still preparing) on any error. */
    suspend fun composeReady(): Boolean {
        if (ctx.manager == null) return true
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor("kotlin-compose") ?: return@runCatching false
                val path = mainPathOf(services, "kotlin-compose")
                ensureMain(services, "kotlin-compose")
                services.composePreviewReady(path)
            }.getOrDefault(false)
        }
    }

    /** Why the lesson [code]'s Compose preview isn't interpretable yet (lowering diagnostics), for the preview
     *  problem chip. Never empty on a failure path — a bare "can't render" with no reason is useless. */
    suspend fun composeDiagnostics(code: String): List<String> {
        if (ctx.manager == null) return listOf("Compose previews aren't available in this build.")
        return withContext(Dispatchers.Default) {
            runCatching {
                val services = scratchFor("kotlin-compose")
                    ?: return@runCatching listOf("The Compose workspace isn't ready yet.")
                val path = mainPathOf(services, "kotlin-compose")
                ensureMain(services, "kotlin-compose")
                composeLowerMutex.withLock {
                    val preview = services.composePreviews(path, code).firstOrNull()
                        ?: return@withLock listOf("No @Preview @Composable found in this snippet.")
                    services.composePreviewDiagnostics(path, code, preview.functionName, preview.arity)
                        .ifEmpty { listOf("The preview lowered with no diagnostics; if it doesn't render the gap is in the render path.") }
                }
            }.getOrElse { listOf("Couldn't analyze this Compose snippet: ${reason(it)}") }
        }
    }

    override fun progress(): UiLearnProgress {
        val map = HashMap<String, Set<String>>()
        for (track in tracks) for (lesson in track.lessons) {
            completedSteps(lesson.id).takeIf { it.isNotEmpty() }?.let { map[lesson.id] = it }
        }
        return UiLearnProgress(map)
    }

    override fun markStepComplete(lessonId: String, stepId: String) {
        progressSync.stepCompleted(lessonId, stepId)
        val idx = findLesson(lessonId)?.second?.steps?.indexOfFirst { it.id == stepId } ?: -1
        if (idx >= 0) recordVisit(lessonId, idx)
    }

    override fun recordVisit(lessonId: String, stepIndex: Int) {
        if (findLesson(lessonId) == null) return
        setPref(KEY_RESUME_LESSON, lessonId)
        setPref(KEY_RESUME_STEP, stepIndex.toString())
    }

    override fun resume(): UiResumePoint? {
        val lessonId = pref(KEY_RESUME_LESSON) ?: return null
        val (track, lesson) = findLesson(lessonId) ?: return null
        val lastIndex = (lesson.steps.size - 1).coerceAtLeast(0)
        val step = (pref(KEY_RESUME_STEP)?.toIntOrNull() ?: 0).coerceIn(0, lastIndex)
        val done = completedSteps(lessonId).count { id -> lesson.steps.any { it.id == id } }
        val fraction = if (lesson.steps.isEmpty()) 0f else done.toFloat() / lesson.steps.size
        return UiResumePoint(track.id, lesson.id, step, track.title, lesson.title, fraction)
    }

    // ---- exercise runner ----

    private fun srcInfo(language: String): Pair<String, String> = when {
        // The Compose scratch is the android-library template → module "lib", Kotlin source root.
        language == "kotlin-compose" -> "lib/src/main/kotlin" to "Main.kt"
        language.startsWith("kotlin") -> "app/src/main/kotlin" to "Main.kt"
        else -> "app/src/main/java" to "Main.java"
    }

    private fun mainPathOf(services: IdeServices, language: String): Path {
        val (srcRel, fileName) = srcInfo(language)
        return services.store.rootPath.resolve(srcRel).resolve(fileName)
    }

    /** Ensure a `Main` exists in the scratch module (so completion resolves its module/classpath), returning
     *  its path. The live buffer is completed as an overlay, so a stub is enough when nothing's been run yet. */
    private fun ensureMain(services: IdeServices, language: String): Path {
        val path = mainPathOf(services, language)
        if (!Files.exists(path)) {
            Files.createDirectories(path.parent)
            val stub = if (language == "kotlin") "fun main() {\n}\n"
            else "public class Main {\n    public static void main(String[] args) {\n    }\n}\n"
            Files.write(path, stub.toByteArray(Charsets.UTF_8))
        }
        return path
    }

    /** Overwrite the scratch module's `Main` (default package) with the learner's [code], clearing any prior
     *  lesson source so only this file compiles. */
    private fun writeMain(services: IdeServices, language: String, code: String) {
        val (srcRel, fileName) = srcInfo(language)
        val dir = services.store.rootPath.resolve(srcRel)
        if (Files.isDirectory(dir)) {
            Files.walk(dir).use { s ->
                s.filter { Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java")) }
                    .forEach { runCatching { Files.delete(it) } }
            }
        }
        Files.createDirectories(dir)
        Files.write(dir.resolve(fileName), code.toByteArray(Charsets.UTF_8))
    }

    private fun interpret(cap: RunCapture, check: ExerciseCheck, code: String): UiExerciseResult {
        if (!cap.compiled) {
            val msg = if (cap.diagnostics.isNotEmpty()) "Your code didn't compile — check the errors below and try again."
            else "Your code didn't run. " + (cap.diagnostics.firstOrNull().orEmpty())
            return UiExerciseResult(passed = false, compiled = false, output = cap.stdout, message = msg.trim(), diagnostics = cap.diagnostics)
        }
        val actual = normalize(cap.stdout, check.caseSensitive)
        val outputOk = when {
            check.expectedOutput != null -> actual == normalize(check.expectedOutput, check.caseSensitive)
            check.mustContain.isNotEmpty() -> check.mustContain.all { actual.contains(normalize(it, check.caseSensitive)) }
            else -> cap.exitCode == 0
        }
        // Anti-hardcoding: the required constructs must actually appear in the SOURCE (comments + string
        // literals stripped), so printing the expected answer as a literal doesn't pass the exercise.
        val missing = missingConstructs(code, check.requireSource)
        val ok = outputOk && missing.isEmpty()
        val message = when {
            ok -> "Correct, nicely done!"
            !outputOk && check.expectedOutput != null -> "Not quite. Expected output:\n${check.expectedOutput}"
            !outputOk && check.mustContain.isNotEmpty() -> "Not quite. Your output should include: ${check.mustContain.joinToString(", ")}"
            !outputOk -> "Not quite. Give it another try."
            // Output matched but a construct is missing → the learner hardcoded the answer.
            else -> "Your output is right, but the exercise wants you to actually use ${missing.joinToString(", ") { "`$it`" }} — not just print the answer."
        }
        return UiExerciseResult(passed = ok, compiled = true, output = cap.stdout, message = message, diagnostics = cap.diagnostics)
    }

    private fun normalize(s: String, caseSensitive: Boolean): String {
        val t = s.replace("\r\n", "\n").lines().joinToString("\n") { it.trimEnd() }.trim()
        return if (caseSensitive) t else t.lowercase()
    }

    private fun failure(message: String) = UiExerciseResult(passed = false, compiled = false, message = message)
    private fun reason(e: Throwable) = e.message ?: e.javaClass.simpleName

    // ---- content mapping (drops the server-side ExerciseCheck) ----

    private fun LearnTrackDef.toUiTrack() = UiLearnTrack(
        id, title, subtitle, iconId, accentColor, language, category, lessons.map { it.toUiSummary() },
        level = level, prerequisites = prerequisites,
    )

    private fun LearnLessonDef.toUiSummary() =
        UiLessonSummary(id, title, summary, iconId, estMinutes, steps.size, kind)

    private fun LearnStepDef.toUiStep(): UiLessonStep = when (this) {
        is LearnStepDef.Concept -> UiLessonStep.Concept(id, title, blocks)
        is LearnStepDef.Interactive ->
            UiLessonStep.Interactive(id, title, blocks, starterCode.trimIndent(), language, hints, solution.trimIndent())
        is LearnStepDef.Quiz -> UiLessonStep.Quiz(id, title, prompt, options, correctIndex, explanation)
    }

    private fun findLesson(lessonId: String): Pair<LearnTrackDef, LearnLessonDef>? {
        for (track in tracks) for (lesson in track.lessons) if (lesson.id == lessonId) return track to lesson
        return null
    }

    private fun findStep(lessonId: String, stepId: String): LearnStepDef? =
        findLesson(lessonId)?.second?.steps?.firstOrNull { it.id == stepId }

    // ---- account ----

    override suspend fun syncProgress(): Boolean = runCatching { progressSync.sync() }.getOrDefault(false)

    override fun account(): UiLearnAccount {
        val signedIn = remote.learnRemoteAvailable() && remote.accountId() != null
        if (!signedIn) return UiLearnAccount(signedIn = false, available = remote.learnRemoteAvailable())
        val state = progressSync.account
        val streak = state?.streak ?: progressSync.cachedStreak() ?: dev.ide.store.LearnStreak()
        return UiLearnAccount(
            signedIn = true,
            available = true,
            streak = streak.current,
            bestStreak = streak.best,
            activeToday = streak.activeToday,
            // Before the first sync of a session, the device's own finished tracks stand in for the badges.
            badges = (state?.badges?.map { it.trackId to it.earnedAt } ?: progressSync.completedTracks().map { it to "" })
                .mapNotNull { (id, at) -> badgeFor(id, at) },
        )
    }

    override suspend fun publicProfile(handle: String): UiLearnPublicProfile? = withContext(Dispatchers.IO) {
        val r = runCatching { remote.publicProfile(handle) }.getOrNull()
        val p = (r as? dev.ide.store.StoreResult.Ok)?.value ?: return@withContext null
        UiLearnPublicProfile(
            badges = p.badges.mapNotNull { badgeFor(it.trackId, it.earnedAt) },
            streak = p.streak.current,
            bestStreak = p.streak.best,
            lessonsStarted = p.lessonsStarted,
        )
    }

    // ---- community ----

    override fun communityAvailable(): Boolean = community.available()
    override suspend fun threads(lessonId: String, stepId: String?) = community.threads(lessonId, stepId)
    override suspend fun threadCount(lessonId: String) = community.threadCount(lessonId)
    override suspend fun thread(threadId: String) = community.thread(threadId)
    override suspend fun askQuestion(
        lessonId: String, stepId: String?, title: String, body: String, code: String?, errorText: String?,
    ) = community.ask(
        lessonId, stepId, title, body, code,
        language = stepId?.let { (findStep(lessonId, it) as? LearnStepDef.Interactive)?.language },
        errorText = errorText,
    )
    override suspend fun answer(threadId: String, body: String, code: String?) = community.answer(threadId, body, code)
    override suspend fun vote(answerId: String, up: Boolean) = community.vote(answerId, up)
    override suspend fun accept(threadId: String, answerId: String?) = community.accept(threadId, answerId)
    override suspend fun deleteMine(kind: String, id: String) = community.deleteMine(kind, id)
    override suspend fun report(kind: String, id: String, reason: String, detail: String?) = community.report(kind, id, reason, detail)
    override suspend fun solutions(lessonId: String, stepId: String, sort: String) = community.solutions(lessonId, stepId, sort)
    override suspend fun likeSolution(solutionId: String, like: Boolean) = community.likeSolution(solutionId, like)
    override fun shareSolutions(): Boolean =
        progressSync.account?.shareSolutions ?: (pref(KEY_SHARE_SOLUTIONS) != "false")
    override suspend fun setShareSolutions(share: Boolean): String? {
        val error = community.setShareSolutions(share)
        if (error == null) {
            setPref(KEY_SHARE_SOLUTIONS, share.toString())
            progressSync.account?.let { progressSync.overrideShareSolutions(share) }
        }
        return error
    }
    override suspend fun reportQueue() = community.reportQueue()
    override suspend fun resolveReport(reportId: String, hide: Boolean) = community.resolveReport(reportId, hide)

    /** A badge for a track this catalog knows; a track id from a newer catalog is left out. */
    private fun badgeFor(trackId: String, earnedAt: String): UiLearnBadge? {
        val t = tracks.firstOrNull { it.id == trackId } ?: return null
        return UiLearnBadge(t.id, t.title, t.iconId, t.accentColor, earnedAt)
    }

    // ---- progress persistence (app-global preferences) ----

    private fun completedSteps(lessonId: String): Set<String> = progressSync.completedSteps(lessonId)

    private fun pref(key: String): String? = ctx.manager?.preference(key)
    private fun setPref(key: String, value: String) { ctx.manager?.setPreference(key, value) }

    private companion object {
        const val KEY_RESUME_LESSON = "learn.resume.lesson"
        const val KEY_RESUME_STEP = "learn.resume.step"

        /** The last known "share my solutions" choice, for before the first sync of a session. */
        const val KEY_SHARE_SOLUTIONS = "learn.shareSolutions"

        /** Per workspace: the lesson/step whose starter its file holds, so re-entering a step keeps the work. */
        const val KEY_WORKSPACE_STEP = "learn.workspace.step."

        /** Per workspace: when the current step was first opened, for "since the step started" checks. */
        const val KEY_WORKSPACE_START = "learn.workspace.start."

        /** [runBaseline]'s value when no run had happened when the step was entered. */
        const val NO_RUN = -1

        /** Cap [prepare]: creating the scratch + (first time) downloading coroutines + the first index build.
         *  A slow start shouldn't block the lesson forever — the gate opens and the editor catches up. */
        const val PREPARE_WAIT_MS = 40_000L

        /** Cap [prepare] for the Compose scratch: the first-time `androidx.compose.*` download is much larger
         *  than coroutines, so allow more time before the gate opens anyway. */
        const val COMPOSE_PREPARE_WAIT_MS = 180_000L

        /** A phone-sized owned render for checking a layout exercise (the size does not change the verdict). */
        val LAYOUT_CHECK_REQUEST = dev.ide.preview.PreviewRequest(1080, 1920, 2.75f, showChrome = false)

        /** The coroutines runtime for interactive coroutine lessons (matches the app's catalog version). */
        const val COROUTINES_COORD = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0"

        /** The `androidx.compose.*` set the Compose scratch resolves so a `@Composable` lowers (`Text`/`Column`/
         *  `Button` resolve to real FQNs the call resolver's top-level-function index finds — the same AARs the
         *  editor's Compose preview uses on a real project). Matches the app's Compose build-feature set
         *  ([dev.ide.android.support.AndroidFeatureDependencies.COMPOSE]); rendering dispatches against the
         *  device's bundled Compose. Attaches only because the scratch is an ANDROID module (`android-library`);
         *  `activity-compose` is omitted — a preview never needs an Activity. */
        val COMPOSE_COORDS = listOf(
            "androidx.compose.runtime:runtime:1.11.2",
            "androidx.compose.ui:ui:1.11.2",
            "androidx.compose.foundation:foundation:1.11.2",
            "androidx.compose.material3:material3:1.4.0",
            "androidx.compose.ui:ui-tooling-preview:1.11.2",
        )
    }
}

/** The [required] source patterns absent from [code] after stripping comments + string-literal contents and
 *  whitespace (so a required call/definition matches regardless of spacing, and can't be faked inside a
 *  comment or a printed string). Powers the anti-hardcoding exercise check. */
internal fun missingConstructs(code: String, required: List<String>): List<String> {
    if (required.isEmpty()) return emptyList()
    val stripped = stripCommentsAndStrings(code).filterNot { it.isWhitespace() }
    return required.filter { pat -> !stripped.contains(pat.filterNot { it.isWhitespace() }) }
}

/** Remove `//`/`/* */` comments and the CONTENTS of string/char literals (keep the quotes), so a
 *  source-construct check can't be satisfied by text hidden in a comment or a printed string. */
internal fun stripCommentsAndStrings(code: String): String {
    val sb = StringBuilder(code.length)
    var i = 0
    val n = code.length
    while (i < n) {
        val c = code[i]
        when {
            c == '/' && i + 1 < n && code[i + 1] == '/' -> { i += 2; while (i < n && code[i] != '\n') i++ }
            c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                i += 2; while (i < n && !(code[i] == '*' && i + 1 < n && code[i + 1] == '/')) i++; i = (i + 2).coerceAtMost(n)
            }
            c == '"' || c == '\'' -> {
                sb.append(c); i++
                while (i < n && code[i] != c && code[i] != '\n') { if (code[i] == '\\') i++; i++ }
                if (i < n && code[i] == c) { sb.append(c); i++ }
            }
            else -> { sb.append(c); i++ }
        }
    }
    return sb.toString()
}
