package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.store.DailyBoard
import dev.ide.store.DailyChallengeService
import dev.ide.store.DailyDay
import dev.ide.store.DailyJudgeEvent
import dev.ide.store.DailyHistoryEntry
import dev.ide.store.DailyLanguage
import dev.ide.store.DailyLanguageMaterial
import dev.ide.store.DailyProbePoint
import dev.ide.store.DailyProblem
import dev.ide.store.DailyProfile
import dev.ide.store.DailyRank
import dev.ide.store.DailySample
import dev.ide.store.DailySession
import dev.ide.store.DailySolution
import dev.ide.store.DailySolutionEntry
import dev.ide.store.DailyStreak
import dev.ide.store.DailyVerdict
import dev.ide.store.DailyYou
import dev.ide.store.StoreResult
import dev.ide.store.impl.SupabaseAiCreditsService.Companion.aiCreditsSession
import dev.ide.store.impl.platform.StoreHttp

/**
 * The daily challenge transport.
 *
 * Reads go to Supabase RPCs, which are `security definer` and filter by date in their own bodies, so a
 * client cannot ask for tomorrow's problem. The submission goes somewhere else entirely: the judge is a
 * separate service that holds the hidden tests and the service-role key, and it is the only thing that
 * can write a verdict.
 *
 * Everything is `HttpURLConnection` and POST, matching the rest of the store. A PATCH would work on
 * Android and fail on the desktop JDK, which is how a mutation ends up silently reporting "offline" on
 * one platform only.
 */
class SupabaseDailyService(
    url: String,
    private val apiKey: String,
    /** Where the judge is deployed. Blank disables submitting while browsing still works. */
    judgeUrl: String,
    /**
     * The session's owner, for the signed-in user's access token. The same shape the free AI tier takes, so a
     * process that holds no session of its own (the iOS app's engine) can lend the app's.
     */
    private val accounts: AiCreditsSession,
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 20_000,
    /** Judging compiles and runs code on a server, so its ceiling is minutes rather than seconds. */
    judgeTimeoutMs: Int = 240_000,
) : DailyChallengeService {

    /** Over the account service in this process, which owns the session. */
    constructor(
        url: String,
        apiKey: String,
        judgeUrl: String,
        accounts: SupabaseAccountService,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
        judgeTimeoutMs: Int = 240_000,
    ) : this(url, apiKey, judgeUrl, accounts.aiCreditsSession(), connectTimeoutMs, readTimeoutMs, judgeTimeoutMs)

    private val http = StoreHttp(connectTimeoutMs, readTimeoutMs)

    /** The judge's own client: it compiles and runs the submission, so it waits far longer. */
    private val judgeHttp = StoreHttp(connectTimeoutMs, judgeTimeoutMs)
    private val base = url.trimEnd('/')
    private val judge = judgeUrl.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun challengesAvailable(): Boolean = configured

    override fun day(date: String?): StoreResult<DailyDay> =
        rpc("daily_challenge", JsonWriter.obj("p_date" to dateParam(date))).map { parseDay(it) }

    override fun start(date: String?): StoreResult<DailySession> {
        if (accounts.bearer() == null) return StoreResult.Failed("Sign in to start the challenge")
        return rpc("daily_start", JsonWriter.obj("p_date" to dateParam(date))).flatMap { root ->
            if (JsonReader.bool(root, "ok")) {
                StoreResult.Ok(DailySession(JsonReader.str(root, "startedAt"), JsonReader.str(root, "serverNow")))
            } else {
                StoreResult.Failed(explainStart(JsonReader.str(root, "reason")))
            }
        }
    }

    override fun board(date: String?, limit: Int, offset: Int): StoreResult<DailyBoard> =
        rpc(
            "daily_leaderboard",
            JsonWriter.obj("p_date" to dateParam(date), "p_limit" to limit, "p_offset" to offset),
        ).map { parseBoard(it) }

    override fun solution(date: String?, handle: String): StoreResult<DailySolution> =
        rpc("daily_solution", JsonWriter.obj("p_date" to dateParam(date), "p_handle" to handle))
            .map { parseSolution(it) }

    override fun profile(limit: Int): StoreResult<DailyProfile> {
        if (accounts.bearer() == null) return StoreResult.Ok(DailyProfile())
        return rpc("daily_me", JsonWriter.obj("p_limit" to limit)).map { parseProfile(it) }
    }

    override fun submit(date: String, language: DailyLanguage, source: String): StoreResult<DailyVerdict> {
        if (judge.isBlank()) return StoreResult.Unavailable("Judging is not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in to submit")

        val body = JsonWriter.obj("date" to date, "language" to language.wire, "source" to source)
        val r = judgeHttp.request(
            method = "POST",
            url = "$judge/api/judge",
            headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"),
            body = body,
        )
        if (r.error != null) return StoreResult.Unavailable(r.error)
        val root = JsonReader.parseOrNull(r.body)
        return if (r.status in 200..299) {
            StoreResult.Ok(parseVerdict(root))
        } else {
            // The judge explains refusals in words meant for a person, so they are shown as sent.
            StoreResult.Failed(JsonReader.str(root, "message") ?: "The judge refused that submission.", r.status)
        }
    }

    override fun submit(
        date: String,
        language: DailyLanguage,
        source: String,
        onEvent: (DailyJudgeEvent) -> Unit,
    ): StoreResult<DailyVerdict> {
        if (judge.isBlank()) return StoreResult.Unavailable("Judging is not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in to submit")

        val body = JsonWriter.obj("date" to date, "language" to language.wire, "source" to source)
        var outcome: StoreResult<DailyVerdict>? = null
        val r = judgeHttp.requestLines(
            method = "POST",
            url = "$judge/api/judge",
            headers = mapOf(
                "Content-Type" to "application/json",
                // Asks for the steps as they happen. A judge that predates streaming ignores it and
                // answers with the single verdict object, which is read below as the last line.
                "Accept" to "application/x-ndjson, application/json",
                "Authorization" to "Bearer $token",
            ),
            body = body,
        ) { line ->
            val root = JsonReader.parseOrNull(line) ?: return@requestLines
            when (JsonReader.str(root, "type")) {
                "step" -> parseJudgeEvent(root)?.let { event ->
                    try { onEvent(event) } catch (_: Exception) { /* a display problem never loses a verdict */ }
                }
                "result" -> outcome = StoreResult.Ok(parseVerdict(JsonReader.obj(root)?.get("outcome")))
                // The status line was already sent as 200, so a refusal arrives as a line with its real status.
                "error" -> outcome = StoreResult.Failed(
                    JsonReader.str(root, "message") ?: "The judge refused that submission.",
                    JsonReader.int(root, "status"),
                )
                null -> if (JsonReader.str(root, "verdict") != null) outcome = StoreResult.Ok(parseVerdict(root))
            }
        }
        if (r.error != null) return StoreResult.Unavailable(r.error)
        if (r.status !in 200..299) {
            val root = JsonReader.parseOrNull(r.body)
            return StoreResult.Failed(JsonReader.str(root, "message") ?: "The judge refused that submission.", r.status)
        }
        // A stream cut before its last line: the judgement may still have been recorded, so say so rather
        // than call it a failure of the solution.
        return outcome ?: StoreResult.Failed(
            "The connection to the judge closed before the verdict arrived. Check your history in a moment: it may have been recorded.",
        )
    }

    private fun parseJudgeEvent(root: Any?): DailyJudgeEvent? {
        val step = JsonReader.str(root, "step") ?: return null
        val state = JsonReader.str(root, "state") ?: return null
        val detail = JsonReader.obj(JsonReader.obj(root)?.get("detail"))
            ?.entries
            ?.mapNotNull { (k, v) -> if (k is String && v != null) k to plain(v) else null }
            ?.toMap()
            .orEmpty()
        val ms = JsonReader.long(root, "ms", -1).takeIf { it >= 0 }
        return DailyJudgeEvent(step, state, ms, detail)
    }

    /** JSON numbers come back as doubles; 706.0 should read as 706. */
    private fun plain(v: Any): String = when (v) {
        is Double -> if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        is Float -> plain(v.toDouble())
        else -> v.toString()
    }

    // -------------------------------------------------------------------------------------------------

    /**
     * The `p_date` to send for [date]: null (the server's today) when it is blank. The app names today with an
     * empty string, and Postgres refuses `''` as a date, so sending it made "Solve today's challenge" a
     * spinner that never ended. The judge needs no such care: it reads anything that is not a date as today.
     */
    private fun dateParam(date: String?): String? = date?.takeIf { it.isNotBlank() }

    private fun rpc(name: String, body: String): StoreResult<Any?> {
        if (!configured) return StoreResult.Unavailable("Challenges are not configured in this build")
        val token = accounts.bearer()
        val first = send(name, body, token)
        // A challenge screen is left open for as long as it takes to solve the problem, which is long
        // enough for the access token underneath it to expire. Refresh once and go again, so submitting
        // an answer does not come back as "sign in" to someone who is signed in.
        if (first is StoreResult.Failed && first.status == 401 && token != null) {
            val fresh = accounts.reauthorize(token) ?: return first
            return send(name, body, fresh)
        }
        return first
    }

    private fun send(name: String, body: String, token: String?): StoreResult<Any?> {
        val r = http.request(
            method = "POST",
            url = "$base/rest/v1/rpc/$name",
            headers = mapOf(
                "Content-Type" to "application/json",
                "apikey" to apiKey,
                // Signed in the call carries the user's token so auth.uid() resolves; anonymously it
                // carries the publishable key, which is what makes browsing work without an account.
                "Authorization" to "Bearer ${token ?: apiKey}",
            ),
            body = body,
        )
        if (r.error != null) return StoreResult.Unavailable(r.error)
        if (r.status !in 200..299) return StoreResult.Failed("$name failed", r.status)
        return StoreResult.Ok(JsonReader.parseOrNull(r.body))
    }

    private fun explainStart(reason: String?): String = when (reason) {
        "auth" -> "Sign in to start the challenge"
        "closed" -> "That day is closed"
        "none" -> "No challenge is scheduled today"
        else -> "Could not start the challenge"
    }

    private fun parseDay(root: Any?): DailyDay {
        val date = JsonReader.str(root, "date").orEmpty()
        if (!JsonReader.bool(root, "scheduled")) {
            return DailyDay(date = date, scheduled = false, reason = JsonReader.str(root, "reason"))
        }
        val p = JsonReader.obj(root)?.get("problem")
        val stats = JsonReader.obj(root)?.get("stats")
        return DailyDay(
            date = date,
            scheduled = true,
            nextDropAt = JsonReader.str(root, "nextDropAt"),
            problem = parseProblem(p),
            solvedCount = JsonReader.int(stats, "solved"),
            solverCount = JsonReader.int(stats, "solvers"),
            you = parseYou(JsonReader.obj(root)?.get("you")),
        )
    }

    private fun parseProblem(p: Any?): DailyProblem? {
        val id = JsonReader.str(p, "id") ?: return null
        val languages = JsonReader.obj(JsonReader.obj(p)?.get("languages")).orEmpty()
        return DailyProblem(
            id = id,
            slug = JsonReader.str(p, "slug").orEmpty(),
            title = JsonReader.str(p, "title").orEmpty(),
            difficulty = JsonReader.str(p, "difficulty") ?: "medium",
            statement = JsonReader.str(p, "statement").orEmpty(),
            constraints = JsonReader.str(p, "constraints"),
            hint = JsonReader.str(p, "hint"),
            tags = JsonReader.strings(p, "tags"),
            timeLimitMs = JsonReader.int(p, "timeLimitMs", 4000),
            compareMode = JsonReader.str(p, "compareMode") ?: "exact",
            featuredLanguage = DailyLanguage.of(JsonReader.str(p, "featuredLanguage")),
            optimalTime = JsonReader.str(p, "optimalTime"),
            optimalSpace = JsonReader.str(p, "optimalSpace"),
            languages = languages.mapNotNull { (key, value) -> parseMaterial(key, value) }.associateBy { it.language },
            samples = JsonReader.arr(JsonReader.obj(p)?.get("samples")).mapNotNull { parseSample(it) },
        )
    }

    private fun parseMaterial(key: String, value: Any?): DailyLanguageMaterial? {
        val harness = JsonReader.str(value, "harness") ?: return null
        val signature = JsonReader.obj(value)?.get("signature")
        val params = JsonReader.arr(JsonReader.obj(signature)?.get("params")).mapNotNull {
            val name = JsonReader.str(it, "name") ?: return@mapNotNull null
            name to (JsonReader.str(it, "type") ?: "int")
        }
        return DailyLanguageMaterial(
            language = DailyLanguage.of(key),
            stub = JsonReader.str(value, "stub").orEmpty(),
            harness = harness,
            signatureJson = JsonWriter.value(signature),
            functionName = JsonReader.str(signature, "name") ?: "solve",
            returnType = JsonReader.str(signature, "returns") ?: "int",
            parameters = params,
        )
    }

    private fun parseSample(v: Any?): DailySample? {
        val obj = JsonReader.obj(v) ?: return null
        return DailySample(
            idx = JsonReader.int(v, "idx"),
            inputJson = JsonWriter.value(obj["input"]),
            expectedJson = JsonWriter.value(obj["expected"]),
            explanation = JsonReader.str(v, "explanation"),
        )
    }

    private fun parseYou(v: Any?): DailyYou {
        if (v == null || !JsonReader.bool(v, "signedIn")) return DailyYou()
        return DailyYou(
            signedIn = true,
            startedAt = JsonReader.str(v, "startedAt"),
            attempts = JsonReader.int(v, "attempts"),
            solved = JsonReader.bool(v, "solved"),
            language = JsonReader.str(v, "language")?.let { DailyLanguage.of(it) },
            timeClass = JsonReader.str(v, "timeClass"),
            spaceClass = JsonReader.str(v, "spaceClass"),
            runtimeNs = JsonReader.long(v, "runtimeNs"),
            solveMs = JsonReader.long(v, "solveMs"),
            streak = JsonReader.int(v, "streak"),
        )
    }

    private fun parseBoard(root: Any?): DailyBoard = DailyBoard(
        date = JsonReader.str(root, "date").orEmpty(),
        total = JsonReader.int(root, "total"),
        rows = JsonReader.arr(JsonReader.obj(root)?.get("rows")).mapNotNull { parseRank(it) },
        you = JsonReader.obj(root)?.get("you")?.let { parseRank(it) },
        solutionsOpen = JsonReader.bool(root, "solutionsOpen"),
    )

    private fun parseSolution(root: Any?): DailySolution {
        val problem = JsonReader.obj(root)?.get("problem")
        return DailySolution(
            date = JsonReader.str(root, "date").orEmpty(),
            open = JsonReader.bool(root, "open"),
            reason = JsonReader.str(root, "reason"),
            title = JsonReader.str(problem, "title"),
            slug = JsonReader.str(problem, "slug"),
            optimalTime = JsonReader.str(problem, "optimalTime"),
            optimalSpace = JsonReader.str(problem, "optimalSpace"),
            measuredBy = JsonReader.str(problem, "measuredBy") ?: "ns",
            reference = parsePoints(JsonReader.obj(root)?.get("reference")),
            solution = parseSolutionEntry(JsonReader.obj(root)?.get("solution")),
            you = parseSolutionEntry(JsonReader.obj(root)?.get("you")),
        )
    }

    private fun parseSolutionEntry(v: Any?): DailySolutionEntry? {
        val handle = JsonReader.str(v, "handle") ?: return null
        return DailySolutionEntry(
            rank = JsonReader.int(v, "rank"),
            handle = handle,
            displayName = JsonReader.str(v, "displayName") ?: handle,
            avatarUrl = JsonReader.str(v, "avatarUrl"),
            score = JsonReader.int(v, "score"),
            language = DailyLanguage.of(JsonReader.str(v, "language")),
            timeClass = JsonReader.str(v, "timeClass").orEmpty(),
            spaceClass = JsonReader.str(v, "spaceClass").orEmpty(),
            timeSteps = JsonReader.int(v, "timeSteps"),
            spaceSteps = JsonReader.int(v, "spaceSteps"),
            runtimeNs = JsonReader.long(v, "runtimeNs"),
            allocBytes = JsonReader.long(v, "allocBytes"),
            solveMs = JsonReader.long(v, "solveMs"),
            attempts = JsonReader.int(v, "attempts"),
            acceptedAt = JsonReader.str(v, "acceptedAt"),
            source = JsonReader.str(v, "source").orEmpty(),
            growthExponent = JsonReader.float(v, "growthExponent")?.toDouble(),
            probe = parsePoints(JsonReader.obj(v)?.get("probe")),
            testsPassed = JsonReader.int(v, "testsPassed"),
            testsTotal = JsonReader.int(v, "testsTotal"),
            isYou = JsonReader.bool(v, "isYou"),
        )
    }

    private fun parsePoints(v: Any?): List<DailyProbePoint> = JsonReader.arr(v).map {
        DailyProbePoint(JsonReader.int(it, "n"), JsonReader.long(it, "ns"), JsonReader.long(it, "bytes"))
    }

    private fun parseRank(v: Any?): DailyRank? {
        val handle = JsonReader.str(v, "handle") ?: return null
        return DailyRank(
            rank = JsonReader.int(v, "rank"),
            handle = handle,
            displayName = JsonReader.str(v, "displayName") ?: handle,
            avatarUrl = JsonReader.str(v, "avatarUrl"),
            score = JsonReader.int(v, "score"),
            language = DailyLanguage.of(JsonReader.str(v, "language")),
            timeClass = JsonReader.str(v, "timeClass").orEmpty(),
            spaceClass = JsonReader.str(v, "spaceClass").orEmpty(),
            runtimeNs = JsonReader.long(v, "runtimeNs"),
            allocBytes = JsonReader.long(v, "allocBytes"),
            solveMs = JsonReader.long(v, "solveMs"),
            attempts = JsonReader.int(v, "attempts"),
            acceptedAt = JsonReader.str(v, "acceptedAt"),
            isYou = JsonReader.bool(v, "isYou"),
        )
    }

    private fun parseProfile(root: Any?): DailyProfile {
        if (!JsonReader.bool(root, "signedIn")) return DailyProfile()
        val player = JsonReader.obj(root)?.get("player")
        val streak = JsonReader.obj(root)?.get("streak")
        return DailyProfile(
            signedIn = true,
            handle = JsonReader.str(player, "handle"),
            displayName = JsonReader.str(player, "displayName"),
            avatarUrl = JsonReader.str(player, "avatarUrl"),
            streak = DailyStreak(
                current = JsonReader.int(streak, "current"),
                longest = JsonReader.int(streak, "longest"),
                totalSolved = JsonReader.int(streak, "totalSolved"),
                lastDate = JsonReader.str(streak, "lastDate"),
            ),
            history = JsonReader.arr(JsonReader.obj(root)?.get("history")).mapNotNull { entry ->
                val date = JsonReader.str(entry, "date") ?: return@mapNotNull null
                DailyHistoryEntry(
                    date = date,
                    slug = JsonReader.str(entry, "slug").orEmpty(),
                    title = JsonReader.str(entry, "title").orEmpty(),
                    difficulty = JsonReader.str(entry, "difficulty") ?: "medium",
                    solved = JsonReader.bool(entry, "solved"),
                    language = JsonReader.str(entry, "language")?.let { DailyLanguage.of(it) },
                    timeClass = JsonReader.str(entry, "timeClass"),
                    runtimeNs = JsonReader.long(entry, "runtimeNs"),
                    solveMs = JsonReader.long(entry, "solveMs"),
                    attempts = JsonReader.int(entry, "attempts"),
                )
            },
        )
    }

    private fun parseVerdict(root: Any?): DailyVerdict {
        val result = JsonReader.obj(root)?.get("result")
        return DailyVerdict(
            verdict = JsonReader.str(root, "verdict") ?: "internal_error",
            testsPassed = JsonReader.int(root, "testsPassed"),
            testsTotal = JsonReader.int(root, "testsTotal"),
            failedSample = JsonReader.obj(root)?.get("failedTest")?.let { JsonReader.int(root, "failedTest") },
            message = JsonReader.str(root, "message"),
            timeClass = JsonReader.str(root, "timeClass"),
            spaceClass = JsonReader.str(root, "spaceClass"),
            growthExponent = JsonReader.float(root, "growthExponent")?.toDouble(),
            runtimeNs = JsonReader.long(root, "runtimeNs"),
            allocBytes = JsonReader.long(root, "allocBytes"),
            probe = JsonReader.arr(JsonReader.obj(root)?.get("probe")).map {
                DailyProbePoint(JsonReader.int(it, "n"), JsonReader.long(it, "ns"), JsonReader.long(it, "bytes"))
            },
            referenceProbe = JsonReader.arr(JsonReader.obj(root)?.get("referenceProbe")).map {
                DailyProbePoint(JsonReader.int(it, "n"), JsonReader.long(it, "ns"), JsonReader.long(it, "bytes"))
            },
            optimalTime = JsonReader.str(root, "optimalTime"),
            optimalSpace = JsonReader.str(root, "optimalSpace"),
            practice = JsonReader.bool(root, "practice"),
            judgedMs = JsonReader.int(root, "judgedMs"),
            rank = result?.let { JsonReader.int(it, "rank") },
            total = result?.let { JsonReader.int(it, "total") },
            score = result?.let { JsonReader.int(it, "score") },
            streak = result?.let { JsonReader.int(it, "streak") },
        )
    }
}

private inline fun <T, R> StoreResult<T>.map(transform: (T) -> R): StoreResult<R> = when (this) {
    is StoreResult.Ok -> StoreResult.Ok(transform(value))
    is StoreResult.Unavailable -> StoreResult.Unavailable(reason)
    is StoreResult.Failed -> StoreResult.Failed(message, status)
}

private inline fun <T, R> StoreResult<T>.flatMap(transform: (T) -> StoreResult<R>): StoreResult<R> = when (this) {
    is StoreResult.Ok -> transform(value)
    is StoreResult.Unavailable -> StoreResult.Unavailable(reason)
    is StoreResult.Failed -> StoreResult.Failed(message, status)
}
