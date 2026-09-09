package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.store.DailyBoard
import dev.ide.store.DailyChallengeService
import dev.ide.store.DailyDay
import dev.ide.store.DailyHistoryEntry
import dev.ide.store.DailyLanguage
import dev.ide.store.DailyLanguageMaterial
import dev.ide.store.DailyProbePoint
import dev.ide.store.DailyProblem
import dev.ide.store.DailyProfile
import dev.ide.store.DailyRank
import dev.ide.store.DailySample
import dev.ide.store.DailySession
import dev.ide.store.DailyStreak
import dev.ide.store.DailyVerdict
import dev.ide.store.DailyYou
import dev.ide.store.StoreResult
import java.net.HttpURLConnection
import java.net.URL

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
    private val accounts: SupabaseAccountService,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    /** Judging compiles and runs code on a server, so its ceiling is minutes rather than seconds. */
    private val judgeTimeoutMs: Int = 240_000,
) : DailyChallengeService {

    private val base = url.trimEnd('/')
    private val judge = judgeUrl.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun challengesAvailable(): Boolean = configured

    override fun day(date: String?): StoreResult<DailyDay> =
        rpc("daily_challenge", JsonWriter.obj("p_date" to date)).map { parseDay(it) }

    override fun start(date: String?): StoreResult<DailySession> {
        if (accounts.bearer() == null) return StoreResult.Failed("Sign in to start the challenge")
        return rpc("daily_start", JsonWriter.obj("p_date" to date)).flatMap { root ->
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
            JsonWriter.obj("p_date" to date, "p_limit" to limit, "p_offset" to offset),
        ).map { parseBoard(it) }

    override fun profile(limit: Int): StoreResult<DailyProfile> {
        if (accounts.bearer() == null) return StoreResult.Ok(DailyProfile())
        return rpc("daily_me", JsonWriter.obj("p_limit" to limit)).map { parseProfile(it) }
    }

    override fun submit(date: String, language: DailyLanguage, source: String): StoreResult<DailyVerdict> {
        if (judge.isBlank()) return StoreResult.Unavailable("Judging is not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in to submit")

        val body = JsonWriter.obj("date" to date, "language" to language.wire, "source" to source)
        return try {
            val conn = (URL("$judge/api/judge").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = judgeTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val root = JsonReader.parseOrNull(text)
            if (code in 200..299) {
                StoreResult.Ok(parseVerdict(root))
            } else {
                // The judge explains refusals in words meant for a person, so they are shown as sent.
                StoreResult.Failed(JsonReader.str(root, "message") ?: "The judge refused that submission.", code)
            }
        } catch (e: Exception) {
            StoreResult.Unavailable(e.message ?: "Could not reach the judge")
        }
    }

    // -------------------------------------------------------------------------------------------------

    private fun rpc(name: String, body: String): StoreResult<Any?> {
        if (!configured) return StoreResult.Unavailable("Challenges are not configured in this build")
        return try {
            val conn = (URL("$base/rest/v1/rpc/$name").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", apiKey)
                // Signed in the call carries the user's token so auth.uid() resolves; anonymously it
                // carries the publishable key, which is what makes browsing work without an account.
                setRequestProperty("Authorization", "Bearer ${accounts.bearer() ?: apiKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return StoreResult.Failed("$name failed", code)
            StoreResult.Ok(JsonReader.parseOrNull(text))
        } catch (e: Exception) {
            StoreResult.Unavailable(e.message ?: "Offline")
        }
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
    )

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
