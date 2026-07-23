package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.IdeServices
import dev.ide.core.LoweredComposePreview
import dev.ide.core.services.RunCapture
import dev.ide.lang.hints.InlayHintKind
import dev.ide.ui.backend.LearnService
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiExerciseResult
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiLearnCatalog
import dev.ide.ui.backend.UiLearnProgress
import dev.ide.ui.backend.UiLearnTrack
import dev.ide.ui.backend.UiLesson
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.backend.UiLessonSummary
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiResumePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * [LearnService] for the home screen's Learn tab. Serves the bundled [LearnContent] catalog, persists local
 * progress through the [ProjectManager][dev.ide.core.ProjectManager] preferences, and auto-checks interactive
 * exercises by compiling + running the learner's code in a hidden scratch project ([ProjectManager.scratch]
 * → [IdeServices.runAndCapture]) and comparing its output to the step's [ExerciseCheck]. Exercise answers
 * never leave the backend; only the pass/fail result + captured output are returned. The interface is exactly
 * what a remote, submission-backed lesson catalog will later implement, so the UI does not change.
 */
internal class LearnBackend(private val ctx: BackendContext) : LearnService {

    private val log = dev.ide.platform.log.Log.logger("learn.compose")

    /** Scratch keys whose extra dependencies (e.g. coroutines) have been resolved this session. */
    private val depsEnsured = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Serializes [ensureCompose] so the live preview's per-render `scratchFor` calls can't launch several
     *  concurrent resolutions of the same coordinates before the first sets [depsEnsured]. */
    private val composeDepsMutex = kotlinx.coroutines.sync.Mutex()

    override fun learnAvailable(): Boolean = LearnContent.tracks.isNotEmpty()

    override suspend fun catalog(): UiLearnCatalog =
        UiLearnCatalog(LearnContent.tracks.map { it.toUiTrack() })

    override suspend fun lesson(id: String): UiLesson? {
        val (track, lesson) = findLesson(id) ?: return null
        return UiLesson(lesson.id, track.id, lesson.title, lesson.summary, track.language, lesson.steps.map { it.toUiStep() })
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
        val step = findStep(lessonId, stepId) as? LearnStepDef.Interactive
            ?: return UiExerciseResult(passed = false, compiled = false, message = "This step has no exercise to check.")
        if (ctx.manager == null) {
            return UiExerciseResult(passed = false, compiled = false, message = "Running exercises isn't available in this build.")
        }
        return withContext(Dispatchers.Default) {
            val services = try {
                scratchFor(step.language) ?: return@withContext failure("The exercise runner isn't available.")
            } catch (e: Throwable) {
                return@withContext failure("Couldn't prepare the exercise runner: ${reason(e)}")
            }
            runCatching { writeMain(services, step.language, code) }.exceptionOrNull()?.let {
                return@withContext failure("Couldn't save your code: ${reason(it)}")
            }
            val cap = try {
                services.runAndCapture("app")
            } catch (e: Throwable) {
                return@withContext failure("The exercise runner failed: ${reason(e)}")
            }
            interpret(cap, step.check, code)
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
                val preview = services.composePreviews(path, code).firstOrNull() ?: return@runCatching null
                services.lowerComposePreview(path, code, preview.functionName, preview.arity)
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
                val preview = services.composePreviews(path, code).firstOrNull()
                    ?: return@runCatching listOf("No @Preview @Composable found in this snippet.")
                services.composePreviewDiagnostics(path, code, preview.functionName, preview.arity)
                    .ifEmpty { listOf("The preview lowered with no diagnostics; if it doesn't render the gap is in the render path.") }
            }.getOrElse { listOf("Couldn't analyze this Compose snippet: ${reason(it)}") }
        }
    }

    override fun progress(): UiLearnProgress {
        val map = HashMap<String, Set<String>>()
        for (track in LearnContent.tracks) for (lesson in track.lessons) {
            completedSteps(lesson.id).takeIf { it.isNotEmpty() }?.let { map[lesson.id] = it }
        }
        return UiLearnProgress(map)
    }

    override fun markStepComplete(lessonId: String, stepId: String) {
        val current = completedSteps(lessonId).toMutableSet()
        if (current.add(stepId)) setPref(keyCompleted(lessonId), current.joinToString(","))
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

    private fun LearnTrackDef.toUiTrack() =
        UiLearnTrack(id, title, subtitle, iconId, accentColor, language, category, lessons.map { it.toUiSummary() })

    private fun LearnLessonDef.toUiSummary() =
        UiLessonSummary(id, title, summary, iconId, estMinutes, steps.size)

    private fun LearnStepDef.toUiStep(): UiLessonStep = when (this) {
        is LearnStepDef.Concept -> UiLessonStep.Concept(id, title, blocks)
        is LearnStepDef.Interactive ->
            UiLessonStep.Interactive(id, title, blocks, starterCode.trimIndent(), language, hints, solution.trimIndent())
        is LearnStepDef.Quiz -> UiLessonStep.Quiz(id, title, prompt, options, correctIndex, explanation)
    }

    private fun findLesson(lessonId: String): Pair<LearnTrackDef, LearnLessonDef>? {
        for (track in LearnContent.tracks) for (lesson in track.lessons) if (lesson.id == lessonId) return track to lesson
        return null
    }

    private fun findStep(lessonId: String, stepId: String): LearnStepDef? =
        findLesson(lessonId)?.second?.steps?.firstOrNull { it.id == stepId }

    // ---- progress persistence (app-global preferences) ----

    private fun completedSteps(lessonId: String): Set<String> =
        pref(keyCompleted(lessonId))?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private fun pref(key: String): String? = ctx.manager?.preference(key)
    private fun setPref(key: String, value: String) { ctx.manager?.setPreference(key, value) }
    private fun keyCompleted(lessonId: String) = "learn.completed.$lessonId"

    private companion object {
        const val KEY_RESUME_LESSON = "learn.resume.lesson"
        const val KEY_RESUME_STEP = "learn.resume.step"

        /** Cap [prepare]: creating the scratch + (first time) downloading coroutines + the first index build.
         *  A slow start shouldn't block the lesson forever — the gate opens and the editor catches up. */
        const val PREPARE_WAIT_MS = 40_000L

        /** Cap [prepare] for the Compose scratch: the first-time `androidx.compose.*` download is much larger
         *  than coroutines, so allow more time before the gate opens anyway. */
        const val COMPOSE_PREPARE_WAIT_MS = 180_000L

        /** The coroutines runtime for interactive coroutine lessons (matches the app's catalog version). */
        const val COROUTINES_COORD = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"

        /** The `androidx.compose.*` set the Compose scratch resolves so a `@Composable` lowers (`Text`/`Column`/
         *  `Button` resolve to real FQNs the call resolver's top-level-function index finds — the same AARs the
         *  editor's Compose preview uses on a real project). Matches the app's Compose build-feature set
         *  ([dev.ide.android.support.AndroidFeatureDependencies.COMPOSE]); rendering dispatches against the
         *  device's bundled Compose. Attaches only because the scratch is an ANDROID module (`android-library`);
         *  `activity-compose` is omitted — a preview never needs an Activity. */
        val COMPOSE_COORDS = listOf(
            "androidx.compose.runtime:runtime:1.7.5",
            "androidx.compose.ui:ui:1.7.5",
            "androidx.compose.foundation:foundation:1.7.5",
            "androidx.compose.material3:material3:1.3.1",
            "androidx.compose.ui:ui-tooling-preview:1.7.5",
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
