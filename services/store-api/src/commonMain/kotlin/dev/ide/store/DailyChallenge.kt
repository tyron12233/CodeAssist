package dev.ide.store

/**
 * The daily coding challenge contract.
 *
 * These types mirror what `daily_challenge()`, `daily_leaderboard()` and `daily_me()` return, plus what
 * the judge answers a submission with. One json document per call, so a screen is one round trip.
 *
 * It lives beside the store because it shares the store's session: the same Supabase project, the same
 * sign-in, the same access token. Splitting it into its own module would mean duplicating the transport
 * and the token handling to gain a name.
 *
 * Nothing here knows about Supabase or about the judge's address. `store-impl` supplies both.
 */

/** Which language a challenge is being solved in. Both are always available. */
enum class DailyLanguage(val wire: String) {
    JAVA("java"), KOTLIN("kotlin");

    companion object {
        fun of(raw: String?): DailyLanguage = if (raw?.lowercase() == "java") JAVA else KOTLIN
    }
}

/**
 * One language's material for a problem.
 *
 * [harness] is the program the solution is compiled inside, with `{{USER_CODE}}` where the solution
 * goes. The server judges by filling this same text, which is why it is sent rather than generated on
 * each side: it is the one place the two could disagree about how a solution is called, so there is only
 * one copy of it.
 */
data class DailyLanguageMaterial(
    val language: DailyLanguage,
    val stub: String,
    val harness: String,
    /** The declared signature, as raw json. Rendered in the statement header. */
    val signatureJson: String,
    val functionName: String,
    val returnType: String,
    val parameters: List<Pair<String, String>>,
)

/**
 * A published example.
 *
 * Input and expected are carried as raw json text rather than parsed values: the app never needs to
 * understand them, it only shows them and hands them back to the runner, and re-encoding a parsed value
 * is a chance to change it.
 */
data class DailySample(
    val idx: Int,
    val inputJson: String,
    val expectedJson: String,
    val explanation: String? = null,
)

data class DailyProblem(
    val id: String,
    val slug: String,
    val title: String,
    val difficulty: String,
    val statement: String,
    val constraints: String? = null,
    val hint: String? = null,
    val tags: List<String> = emptyList(),
    val timeLimitMs: Int = 4000,
    /** How answers are compared: exact, unordered, or float. The device run mirrors it. */
    val compareMode: String = "exact",
    val featuredLanguage: DailyLanguage = DailyLanguage.KOTLIN,
    /** Withheld until the caller has solved it: it is the strongest hint the problem has. */
    val optimalTime: String? = null,
    val optimalSpace: String? = null,
    val languages: Map<DailyLanguage, DailyLanguageMaterial> = emptyMap(),
    val samples: List<DailySample> = emptyList(),
)

/** What the signed-in caller has done with today's problem. */
data class DailyYou(
    val signedIn: Boolean = false,
    val startedAt: String? = null,
    val attempts: Int = 0,
    val solved: Boolean = false,
    val language: DailyLanguage? = null,
    val timeClass: String? = null,
    val spaceClass: String? = null,
    val runtimeNs: Long = 0,
    val solveMs: Long = 0,
    val streak: Int = 0,
)

/**
 * One day of the challenge.
 *
 * [scheduled] false is a normal answer, not an error: a day with no problem is possible when the bank
 * runs dry, and the app says so rather than showing an empty challenge.
 */
data class DailyDay(
    val date: String,
    val scheduled: Boolean,
    val reason: String? = null,
    val nextDropAt: String? = null,
    val problem: DailyProblem? = null,
    val solvedCount: Int = 0,
    val solverCount: Int = 0,
    val you: DailyYou = DailyYou(),
)

data class DailyRank(
    val rank: Int,
    val handle: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val score: Int,
    val language: DailyLanguage,
    val timeClass: String,
    val spaceClass: String,
    val runtimeNs: Long,
    val allocBytes: Long,
    val solveMs: Long,
    val attempts: Int,
    val acceptedAt: String? = null,
    val isYou: Boolean = false,
)

data class DailyBoard(
    val date: String,
    val total: Int = 0,
    val rows: List<DailyRank> = emptyList(),
    val you: DailyRank? = null,
)

data class DailyStreak(
    val current: Int = 0,
    val longest: Int = 0,
    val totalSolved: Int = 0,
    val lastDate: String? = null,
)

/** One past day, as it appears in the archive. */
data class DailyHistoryEntry(
    val date: String,
    val slug: String,
    val title: String,
    val difficulty: String,
    val solved: Boolean,
    val language: DailyLanguage? = null,
    val timeClass: String? = null,
    val runtimeNs: Long = 0,
    val solveMs: Long = 0,
    val attempts: Int = 0,
)

data class DailyProfile(
    val signedIn: Boolean = false,
    val handle: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val streak: DailyStreak = DailyStreak(),
    val history: List<DailyHistoryEntry> = emptyList(),
)

/** A measured point on the growth curve, as the app draws it. */
data class DailyProbePoint(val n: Int, val ns: Long, val bytes: Long)

/**
 * What the judge decided.
 *
 * [rank] and [score] describe the user's best result for the day, which is not always this submission:
 * a later attempt that does not improve on an earlier one leaves the standing result alone.
 */
data class DailyVerdict(
    val verdict: String,
    val testsPassed: Int = 0,
    val testsTotal: Int = 0,
    val failedSample: Int? = null,
    val message: String? = null,
    val timeClass: String? = null,
    val spaceClass: String? = null,
    val growthExponent: Double? = null,
    val runtimeNs: Long = 0,
    val allocBytes: Long = 0,
    val probe: List<DailyProbePoint> = emptyList(),
    /** The reference solution's curve at the same sizes, so the two can be drawn together. */
    val referenceProbe: List<DailyProbePoint> = emptyList(),
    val optimalTime: String? = null,
    val optimalSpace: String? = null,
    val practice: Boolean = false,
    val judgedMs: Int = 0,
    val rank: Int? = null,
    val total: Int? = null,
    val score: Int? = null,
    val streak: Int? = null,
) {
    val accepted: Boolean get() = verdict == "accepted"
}

/** When the solve clock started, as the server sees it. */
data class DailySession(val startedAt: String?, val serverNow: String?)

/**
 * The daily challenge port.
 *
 * Reading is anonymous: the tab shows today's problem and the leaderboard before sign-in, and only
 * [start] and [submit] need an account.
 */
interface DailyChallengeService {
    fun challengesAvailable(): Boolean = false

    /** Today's challenge, or a past day. Anonymous callers get everything except their own progress. */
    fun day(date: String? = null): StoreResult<DailyDay> = StoreResult.Unavailable("Challenges are not configured")

    /** Starts the solve clock. The server records the moment; a second call does not restart it. */
    fun start(date: String? = null): StoreResult<DailySession> = StoreResult.Unavailable("Sign in to solve")

    fun board(date: String? = null, limit: Int = 50, offset: Int = 0): StoreResult<DailyBoard> =
        StoreResult.Unavailable("Challenges are not configured")

    fun profile(limit: Int = 30): StoreResult<DailyProfile> = StoreResult.Unavailable("Sign in to see your history")

    /**
     * Submits a solution for judging.
     *
     * This is the only path that decides a verdict, and it runs on the server: the device's own run is
     * for iteration and never reaches the leaderboard.
     */
    fun submit(date: String, language: DailyLanguage, source: String): StoreResult<DailyVerdict> =
        StoreResult.Unavailable("Challenges are not configured")

    object Unsupported : DailyChallengeService
}
