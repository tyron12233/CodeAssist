package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.core.IdeServices
import dev.ide.platform.JsonReader
import dev.ide.store.DailyChallengeService
import dev.ide.store.DailyLanguage
import dev.ide.store.StoreResult
import dev.ide.ui.backend.ChallengeService
import dev.ide.ui.backend.UiChallengeBoard
import dev.ide.ui.backend.UiChallengeDay
import dev.ide.ui.backend.UiChallengeHistoryEntry
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiChallengeMaterial
import dev.ide.ui.backend.UiChallengeProblem
import dev.ide.ui.backend.UiChallengeProfile
import dev.ide.ui.backend.UiChallengeProgress
import dev.ide.ui.backend.UiChallengeRank
import dev.ide.ui.backend.UiChallengeSample
import dev.ide.ui.backend.UiChallengeStreak
import dev.ide.ui.backend.UiChallengeVerdict
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiFoldRegion
import dev.ide.ui.backend.UiGrowthPoint
import dev.ide.lang.hints.InlayHintKind
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiLocalRun
import dev.ide.ui.backend.UiSampleOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.math.abs

/**
 * Daily challenges: the remote half over [DailyChallengeService], and the local half over a scratch
 * project.
 *
 * The two halves are deliberately unequal. [submit] sends source to the judge and reports whatever it
 * decides; nothing here can influence a verdict. [run] compiles and runs the same harness on this device
 * against the published samples, which is worth doing because a solver should not wait on a server round
 * trip to find out they forgot a semicolon, and costs nothing because the samples are already public.
 *
 * The harness comes from the problem rather than being generated here. It is the one text both sides fill
 * in, so a change to how a solution is called cannot make the device and the judge disagree about what
 * they are running.
 */
internal class ChallengeBackend(
    private val ctx: BackendContext,
    private val remote: DailyChallengeService,
) : ChallengeService {

    override fun challengesAvailable(): Boolean = remote.challengesAvailable()

    override suspend fun day(date: String?): UiChallengeDay = withContext(Dispatchers.IO) {
        when (val r = remote.day(date)) {
            is StoreResult.Ok -> r.value.toUi()
            is StoreResult.Unavailable -> UiChallengeDay(reason = "offline")
            is StoreResult.Failed -> UiChallengeDay(reason = "failed")
        }
    }

    override suspend fun start(date: String?): Boolean = withContext(Dispatchers.IO) {
        remote.start(date) is StoreResult.Ok
    }

    override suspend fun board(date: String?, limit: Int): UiChallengeBoard = withContext(Dispatchers.IO) {
        (remote.board(date, limit) as? StoreResult.Ok)?.value?.toUi() ?: UiChallengeBoard(date = date.orEmpty())
    }

    override suspend fun profile(): UiChallengeProfile = withContext(Dispatchers.IO) {
        (remote.profile() as? StoreResult.Ok)?.value?.toUi() ?: UiChallengeProfile()
    }

    override suspend fun submit(
        date: String,
        language: UiChallengeLanguage,
        source: String,
    ): UiChallengeVerdict = withContext(Dispatchers.IO) {
        when (val r = remote.submit(date, language.toStore(), source)) {
            is StoreResult.Ok -> r.value.toUi()
            // A refusal is not a verdict. Showing it as one would tell a solver their correct answer was
            // wrong because their connection dropped.
            is StoreResult.Unavailable -> UiChallengeVerdict(error = "Could not reach the judge. Your attempt was not used.")
            is StoreResult.Failed -> UiChallengeVerdict(error = r.message)
        }
    }

    // ---- the local run -------------------------------------------------------------------------------

    override suspend fun run(
        language: UiChallengeLanguage,
        source: String,
        problem: UiChallengeProblem,
    ): UiLocalRun = withContext(Dispatchers.Default) {
        val material = problem.materials[language]
            ?: return@withContext UiLocalRun(diagnostics = listOf("This challenge has no ${language.wire} stub."))
        if (problem.samples.isEmpty()) {
            return@withContext UiLocalRun(diagnostics = listOf("This challenge has no examples to run against."))
        }
        if (ctx.manager == null) {
            return@withContext UiLocalRun(diagnostics = listOf("Running isn't available in this build."))
        }

        val started = System.currentTimeMillis()
        val services = try {
            scratchFor(language) ?: return@withContext UiLocalRun(diagnostics = listOf("The runner isn't available."))
        } catch (e: Throwable) {
            return@withContext UiLocalRun(diagnostics = listOf("Couldn't prepare the runner: ${reason(e)}"))
        }

        runCatching { writeSolution(services, language, material.harness.replace(USER_CODE, source)) }
            .exceptionOrNull()
            ?.let { return@withContext UiLocalRun(diagnostics = listOf("Couldn't save your code: ${reason(it)}")) }

        val capture = try {
            services.runAndCapture("app", stdin = payloadFor(problem), timeoutMs = LOCAL_RUN_TIMEOUT_MS)
        } catch (e: Throwable) {
            return@withContext UiLocalRun(diagnostics = listOf("The runner failed: ${reason(e)}"))
        }

        if (!capture.compiled) {
            return@withContext UiLocalRun(
                compiled = false,
                diagnostics = capture.diagnostics,
                elapsedMs = System.currentTimeMillis() - started,
            )
        }

        val results = readResults(capture.stdout)
        if (results == null) {
            return@withContext UiLocalRun(
                compiled = true,
                diagnostics = listOf(
                    if (capture.stdout.isBlank()) "The run produced no output."
                    else "The run did not finish. It may have looped forever.",
                ),
                elapsedMs = System.currentTimeMillis() - started,
            )
        }

        val outcomes = problem.samples.mapIndexed { index, sample ->
            val entry = results[index]
            val error = entry?.let { JsonReader.str(it, "err") }
            val actual = entry?.let { JsonReader.obj(it)?.get("v") }
            UiSampleOutcome(
                idx = sample.idx,
                passed = error == null && entry != null &&
                    sameValue(actual, JsonReader.parseOrNull(sample.expectedJson), problem.compareMode),
                expectedJson = sample.expectedJson,
                actualJson = if (entry == null || error != null) null else dev.ide.platform.JsonWriter.value(actual),
                error = error,
            )
        }

        UiLocalRun(
            compiled = true,
            outcomes = outcomes,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * The payload the harness reads from standard input.
     *
     * The sample inputs are passed through as the json text they arrived as. Parsing and re-encoding them
     * would be a chance to change a value on the way to the runner, and nothing here needs to understand
     * them.
     */
    private fun payloadFor(problem: UiChallengeProblem): String {
        val cases = problem.samples.mapIndexed { index, sample ->
            """{"id":$index,"args":${sample.inputJson}}"""
        }
        val budget = (problem.timeLimitMs.toLong() * problem.samples.size).coerceAtLeast(4000)
        return """{"cases":[${cases.joinToString(",")}],"budgetMs":$budget}"""
    }

    /**
     * Pulls the harness's report out of the run's output.
     *
     * The last marked block wins: the solution shares the process and can print anything it likes,
     * including something that looks like a report, and the harness writes its own last.
     */
    private fun readResults(stdout: String): Map<Int, Any?>? {
        val begin = stdout.lastIndexOf(MARKER_BEGIN)
        if (begin < 0) return null
        val end = stdout.indexOf(MARKER_END, begin)
        if (end < 0) return null
        val body = stdout.substring(begin + MARKER_BEGIN.length, end).trim()
        val root = JsonReader.parseOrNull(body) ?: return null
        if (JsonReader.bool(root, "timeout")) return null
        val results = JsonReader.arr(JsonReader.obj(root)?.get("results"))
        if (results.isEmpty() && JsonReader.obj(root)?.containsKey("results") != true) return null
        return results.associateBy { JsonReader.int(it, "id") }
    }

    /**
     * Structural comparison of two decoded values.
     *
     * Numbers compare with a tolerance whatever the problem's mode, because json does not distinguish
     * `2` from `2.0` and a solver should not see a failure for a difference that only exists in the
     * encoding. The server does the comparison that counts.
     */
    private fun sameValue(actual: Any?, expected: Any?, mode: String): Boolean {
        if (mode == "unordered" && actual is List<*> && expected is List<*>) {
            if (actual.size != expected.size) return false
            val remaining = expected.toMutableList()
            for (item in actual) {
                val index = remaining.indexOfFirst { sameValue(item, it, "exact") }
                if (index < 0) return false
                remaining.removeAt(index)
            }
            return true
        }
        if (actual is List<*> || expected is List<*>) {
            if (actual !is List<*> || expected !is List<*> || actual.size != expected.size) return false
            return actual.indices.all { sameValue(actual[it], expected[it], mode) }
        }
        if (actual is Number && expected is Number) {
            val a = actual.toDouble()
            val b = expected.toDouble()
            if (a.isNaN() || b.isNaN()) return false
            return abs(a - b) <= 1e-6 * maxOf(1.0, abs(b))
        }
        if (actual is Map<*, *> && expected is Map<*, *>) {
            if (actual.keys != expected.keys) return false
            return actual.keys.all { sameValue(actual[it], expected[it], mode) }
        }
        return actual == expected
    }

    // ---- editor intelligence, over the same scratch --------------------------------------------------

    override suspend fun complete(
        language: UiChallengeLanguage,
        code: String,
        offset: Int,
    ): UiCompletionResult = withContext(Dispatchers.Default) {
        val empty = UiCompletionResult(emptyList(), offset, offset)
        if (ctx.manager == null) return@withContext empty
        runCatching {
            val services = scratchFor(language) ?: return@runCatching empty
            val path = ensureSolution(services, language)
            val at = offset.coerceIn(0, code.length)
            // Two corrections the lesson editor needed for the same reasons. The replacement range is
            // re-anchored to the identifier before the caret, because a zero-width range makes the editor
            // compute an empty prefix and show the whole unfiltered set. And the result is forced
            // incomplete so the editor re-queries every keystroke rather than narrowing a cached list that
            // may already have truncated the match; the scratch is tiny, so re-querying is cheap.
            services.complete(path, code, at).toUi()
                .copy(replaceStart = identifierStart(code, at), replaceEnd = at, isIncomplete = true)
        }.getOrElse { empty }
    }

    /** Start of the identifier ending at [offset], matching the editor's own token boundary. */
    private fun identifierStart(code: String, offset: Int): Int {
        var i = offset.coerceIn(0, code.length)
        while (i > 0 && code[i - 1].let { it.isLetterOrDigit() || it == '_' || it == '$' }) i--
        return i
    }

    override suspend fun analyze(language: UiChallengeLanguage, code: String): List<UiDiagnostic> =
        withContext(Dispatchers.Default) {
            if (ctx.manager == null) return@withContext emptyList()
            runCatching {
                val services = scratchFor(language) ?: return@runCatching emptyList()
                services.analyzeDiagnostics(ensureSolution(services, language), code).toUiDiagnostics(code)
            }.getOrDefault(emptyList())
        }

    override suspend fun hints(
        language: UiChallengeLanguage,
        code: String,
        startOffset: Int,
        endOffset: Int,
    ): List<UiInlayHint> = withContext(Dispatchers.Default) {
        if (ctx.manager == null) return@withContext emptyList()
        runCatching {
            val services = scratchFor(language) ?: return@runCatching emptyList()
            val start = startOffset.coerceIn(0, code.length)
            val end = endOffset.coerceIn(start, code.length)
            services.inlayHints(ensureSolution(services, language), code, start, end).map { hint ->
                UiInlayHint(
                    offset = hint.offset,
                    // The navigation symbol is dropped: this is a standalone buffer with no project to
                    // jump into, so a clickable type would lead nowhere.
                    parts = hint.parts.map { UiInlayPart(it.text) },
                    kind = when (hint.kind) {
                        InlayHintKind.TYPE -> UiInlayKind.Type
                        InlayHintKind.PARAMETER -> UiInlayKind.Parameter
                        InlayHintKind.CHAINING -> UiInlayKind.Chaining
                        InlayHintKind.OTHER -> UiInlayKind.Other
                    },
                    tooltip = hint.tooltip,
                    paddingLeft = hint.paddingLeft,
                    paddingRight = hint.paddingRight,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun folds(language: UiChallengeLanguage, code: String): List<UiFoldRegion> =
        withContext(Dispatchers.Default) {
            if (ctx.manager == null) return@withContext emptyList()
            runCatching {
                val services = scratchFor(language) ?: return@runCatching emptyList()
                services.codeFolds(ensureSolution(services, language), code).map { fold ->
                    UiFoldRegion(
                        startOffset = fold.range.start,
                        endOffset = fold.range.end,
                        placeholder = fold.placeholder,
                        kind = fold.kind.id,
                        collapsedByDefault = fold.collapsedByDefault,
                    )
                }
            }.getOrDefault(emptyList())
        }

    override suspend fun prepare(language: UiChallengeLanguage): Boolean = withContext(Dispatchers.Default) {
        val services = runCatching { scratchFor(language) }.getOrNull() ?: return@withContext false
        withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
            services.indexStatus.first { !it.building }
            true
        } ?: false
    }

    override suspend fun indexing(language: UiChallengeLanguage): Boolean = withContext(Dispatchers.Default) {
        runCatching { scratchFor(language)?.indexStatus?.value?.building }.getOrNull() ?: false
    }

    // ---- scratch -------------------------------------------------------------------------------------

    /**
     * The hidden project a challenge is compiled in.
     *
     * Separate from the Learn scratches so a lesson in progress is never clobbered by a challenge run,
     * and separate per language because the two need different module types.
     */
    private suspend fun scratchFor(language: UiChallengeLanguage): IdeServices? {
        val manager = ctx.manager ?: return null
        return when (language) {
            UiChallengeLanguage.Kotlin -> manager.scratch("challenge-kotlin", "kotlin-console")
            UiChallengeLanguage.Java -> manager.scratch("challenge-java", "java-console")
        }
    }

    /**
     * Makes sure the solution file exists before the engine is asked about it.
     *
     * Completion and analysis resolve against a file inside a module; asking about a path that is not
     * there yet answers with nothing, which reads as a broken editor rather than an empty project.
     */
    private fun ensureSolution(services: IdeServices, language: UiChallengeLanguage): java.nio.file.Path {
        val path = solutionPath(services, language)
        if (!Files.isRegularFile(path)) {
            Files.createDirectories(path.parent)
            Files.write(path, "".toByteArray(Charsets.UTF_8))
        }
        return path
    }

    private fun solutionPath(services: IdeServices, language: UiChallengeLanguage) =
        services.store.rootPath.resolve(
            if (language == UiChallengeLanguage.Kotlin) "app/src/main/kotlin" else "app/src/main/java",
        ).resolve(if (language == UiChallengeLanguage.Kotlin) "Solution.kt" else "Solution.java")

    /**
     * Writes the filled harness, clearing whatever the previous challenge left behind.
     *
     * Written straight to disk rather than through the editor's save path, so the main-class detection
     * that follows has to scan the live sources rather than trust an index that still names yesterday's
     * class.
     *
     * The engine's overlay for the same path is moved with it. [ensureSolution] points the editor at this
     * very file, so completion and analysis leave the solver's bare snippet there as an open buffer, and a
     * run begins by flushing every open buffer to disk. Leaving the overlay behind therefore puts the
     * snippet back over the harness between this write and the run, and the run fails with "no runnable
     * main() found" — the snippet is a function, the entry point lives in the harness.
     */
    private fun writeSolution(services: IdeServices, language: UiChallengeLanguage, text: String) {
        val path = solutionPath(services, language)
        val dir = path.parent
        if (Files.isDirectory(dir)) {
            Files.walk(dir).use { stream ->
                stream.filter {
                    Files.isRegularFile(it) && (it.toString().endsWith(".kt") || it.toString().endsWith(".java"))
                }.forEach { runCatching { Files.delete(it) } }
            }
        }
        Files.createDirectories(dir)
        Files.write(path, text.toByteArray(Charsets.UTF_8))
        services.updateDocument(path, text)
    }

    private fun reason(t: Throwable): String = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName.orEmpty()

    private companion object {
        const val USER_CODE = "{{USER_CODE}}"
        const val MARKER_BEGIN = "__CA_BEGIN__"
        const val MARKER_END = "__CA_END__"
        const val LOCAL_RUN_TIMEOUT_MS = 60_000L
        const val PREPARE_TIMEOUT_MS = 60_000L
    }
}

// ---- mapping -----------------------------------------------------------------------------------------

private fun UiChallengeLanguage.toStore(): DailyLanguage =
    if (this == UiChallengeLanguage.Java) DailyLanguage.JAVA else DailyLanguage.KOTLIN

private fun DailyLanguage.toUi(): UiChallengeLanguage =
    if (this == DailyLanguage.JAVA) UiChallengeLanguage.Java else UiChallengeLanguage.Kotlin

private fun dev.ide.store.DailyDay.toUi(): UiChallengeDay = UiChallengeDay(
    date = date,
    scheduled = scheduled,
    reason = reason,
    nextDropAtMs = parseInstantMs(nextDropAt),
    problem = problem?.toUi(),
    solvedCount = solvedCount,
    solverCount = solverCount,
    you = UiChallengeProgress(
        signedIn = you.signedIn,
        startedAtMs = parseInstantMs(you.startedAt),
        attempts = you.attempts,
        solved = you.solved,
        language = you.language?.toUi(),
        timeClass = you.timeClass,
        spaceClass = you.spaceClass,
        runtimeNs = you.runtimeNs,
        solveMs = you.solveMs,
        streak = you.streak,
    ),
)

private fun dev.ide.store.DailyProblem.toUi(): UiChallengeProblem = UiChallengeProblem(
    id = id,
    slug = slug,
    title = title,
    difficulty = difficulty,
    statement = statement,
    constraints = constraints,
    hint = hint,
    tags = tags,
    timeLimitMs = timeLimitMs,
    compareMode = compareMode,
    featuredLanguage = featuredLanguage.toUi(),
    optimalTime = optimalTime,
    optimalSpace = optimalSpace,
    materials = languages.entries.associate { (key, value) ->
        key.toUi() to UiChallengeMaterial(
            language = key.toUi(),
            stub = value.stub,
            harness = value.harness,
            functionName = value.functionName,
            returnType = value.returnType,
            parameters = value.parameters,
        )
    },
    samples = samples.map { UiChallengeSample(it.idx, it.inputJson, it.expectedJson, it.explanation) },
)

private fun dev.ide.store.DailyBoard.toUi(): UiChallengeBoard = UiChallengeBoard(
    date = date,
    total = total,
    rows = rows.map { it.toUi() },
    you = you?.toUi(),
)

private fun dev.ide.store.DailyRank.toUi(): UiChallengeRank = UiChallengeRank(
    rank = rank,
    handle = handle,
    displayName = displayName,
    avatarUrl = avatarUrl,
    score = score,
    language = language.toUi(),
    timeClass = timeClass,
    spaceClass = spaceClass,
    runtimeNs = runtimeNs,
    allocBytes = allocBytes,
    solveMs = solveMs,
    attempts = attempts,
    isYou = isYou,
)

private fun dev.ide.store.DailyProfile.toUi(): UiChallengeProfile = UiChallengeProfile(
    signedIn = signedIn,
    handle = handle,
    displayName = displayName,
    avatarUrl = avatarUrl,
    streak = UiChallengeStreak(streak.current, streak.longest, streak.totalSolved),
    history = history.map {
        UiChallengeHistoryEntry(
            date = it.date,
            slug = it.slug,
            title = it.title,
            difficulty = it.difficulty,
            solved = it.solved,
            language = it.language?.toUi(),
            timeClass = it.timeClass,
            runtimeNs = it.runtimeNs,
            solveMs = it.solveMs,
            attempts = it.attempts,
        )
    },
)

private fun dev.ide.store.DailyVerdict.toUi(): UiChallengeVerdict = UiChallengeVerdict(
    verdict = verdict,
    testsPassed = testsPassed,
    testsTotal = testsTotal,
    message = message,
    timeClass = timeClass,
    spaceClass = spaceClass,
    growthExponent = growthExponent,
    runtimeNs = runtimeNs,
    allocBytes = allocBytes,
    growth = probe.map { UiGrowthPoint(it.n, it.ns, it.bytes) },
    referenceGrowth = referenceProbe.map { UiGrowthPoint(it.n, it.ns, it.bytes) },
    optimalTime = optimalTime,
    optimalSpace = optimalSpace,
    practice = practice,
    judgedMs = judgedMs,
    rank = rank,
    total = total,
    score = score,
    streak = streak,
)

/**
 * Parses an ISO instant to epoch millis without a date library.
 *
 * Only the shape Postgres emits is handled, because that is the only shape this receives. Anything else
 * yields zero and the countdown simply does not run, which is better than a crash on a screen whose job
 * is to show a problem.
 */
private fun parseInstantMs(text: String?): Long {
    if (text.isNullOrBlank()) return 0
    return runCatching {
        java.time.OffsetDateTime.parse(
            if (text.endsWith("Z") || text.contains('+') || text.lastIndexOf('-') > 10) text else text + "Z",
        ).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }
}
