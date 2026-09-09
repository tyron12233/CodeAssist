package dev.ide.ui.backend

/**
 * The daily coding challenge seam.
 *
 * Mirrors the store and learn seams: a defaulted service on [IdeBackend] with an [Unsupported] object,
 * so a host that does not provide challenges is unaffected and the screens degrade to an explanation
 * rather than an error.
 *
 * The split that matters runs through this interface. [run] compiles and runs on the device against the
 * published samples, for iteration, and decides nothing. [submit] sends the source to the server, which
 * holds the hidden tests and is the only thing that can produce a verdict or move the leaderboard.
 */

/** Which language a challenge is being solved in. */
enum class UiChallengeLanguage(val wire: String) {
    Java("java"), Kotlin("kotlin");

    companion object {
        fun of(raw: String?): UiChallengeLanguage = if (raw?.lowercase() == "java") Java else Kotlin
    }
}

/** One language's starting point and the program its solution is compiled inside. */
data class UiChallengeMaterial(
    val language: UiChallengeLanguage,
    val stub: String,
    /** Carries `{{USER_CODE}}`. The server fills the same text, so the two cannot disagree. */
    val harness: String,
    val functionName: String,
    val returnType: String,
    val parameters: List<Pair<String, String>>,
)

/** A published example, with its input and expected answer as raw json. */
data class UiChallengeSample(
    val idx: Int,
    val inputJson: String,
    val expectedJson: String,
    val explanation: String? = null,
)

data class UiChallengeProblem(
    val id: String,
    val slug: String,
    val title: String,
    val difficulty: String,
    val statement: String,
    val constraints: String? = null,
    val hint: String? = null,
    val tags: List<String> = emptyList(),
    val timeLimitMs: Int = 4000,
    /** How answers are compared: exact, unordered, or float. */
    val compareMode: String = "exact",
    val featuredLanguage: UiChallengeLanguage = UiChallengeLanguage.Kotlin,
    /** Null until solved: it is the strongest hint the problem has. */
    val optimalTime: String? = null,
    val optimalSpace: String? = null,
    val materials: Map<UiChallengeLanguage, UiChallengeMaterial> = emptyMap(),
    val samples: List<UiChallengeSample> = emptyList(),
)

data class UiChallengeProgress(
    val signedIn: Boolean = false,
    /** Epoch millis the server started the solve clock, so the UI never parses a timestamp. */
    val startedAtMs: Long = 0,
    val attempts: Int = 0,
    val solved: Boolean = false,
    val language: UiChallengeLanguage? = null,
    val timeClass: String? = null,
    val spaceClass: String? = null,
    val runtimeNs: Long = 0,
    val solveMs: Long = 0,
    val streak: Int = 0,
)

/** One day of the challenge. [scheduled] false is a real answer, not a failure. */
data class UiChallengeDay(
    val date: String = "",
    val scheduled: Boolean = false,
    val reason: String? = null,
    /** Epoch millis of the next drop, so the countdown does not depend on parsing a timestamp in the UI. */
    val nextDropAtMs: Long = 0,
    val problem: UiChallengeProblem? = null,
    val solvedCount: Int = 0,
    val solverCount: Int = 0,
    val you: UiChallengeProgress = UiChallengeProgress(),
)

data class UiChallengeRank(
    val rank: Int,
    val handle: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val score: Int,
    val language: UiChallengeLanguage,
    val timeClass: String,
    val spaceClass: String,
    val runtimeNs: Long,
    val allocBytes: Long,
    val solveMs: Long,
    val attempts: Int,
    val isYou: Boolean = false,
)

data class UiChallengeBoard(
    val date: String = "",
    val total: Int = 0,
    val rows: List<UiChallengeRank> = emptyList(),
    val you: UiChallengeRank? = null,
)

data class UiChallengeStreak(
    val current: Int = 0,
    val longest: Int = 0,
    val totalSolved: Int = 0,
)

data class UiChallengeHistoryEntry(
    val date: String,
    val slug: String,
    val title: String,
    val difficulty: String,
    val solved: Boolean,
    val language: UiChallengeLanguage? = null,
    val timeClass: String? = null,
    val runtimeNs: Long = 0,
    val solveMs: Long = 0,
    val attempts: Int = 0,
)

data class UiChallengeProfile(
    val signedIn: Boolean = false,
    val handle: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val streak: UiChallengeStreak = UiChallengeStreak(),
    val history: List<UiChallengeHistoryEntry> = emptyList(),
)

/** A measured point on the growth curve, as the result sheet draws it. */
data class UiGrowthPoint(val n: Int, val ns: Long, val bytes: Long)

/**
 * The server's verdict.
 *
 * [rank] and [score] describe the user's best result for the day, which is not always this submission: a
 * later attempt that does not improve on the standing one leaves it alone.
 */
data class UiChallengeVerdict(
    val verdict: String = "internal_error",
    val testsPassed: Int = 0,
    val testsTotal: Int = 0,
    val message: String? = null,
    val timeClass: String? = null,
    val spaceClass: String? = null,
    val growthExponent: Double? = null,
    val runtimeNs: Long = 0,
    val allocBytes: Long = 0,
    val growth: List<UiGrowthPoint> = emptyList(),
    /** The reference solution's curve at the same sizes. The chart is meaningless without it. */
    val referenceGrowth: List<UiGrowthPoint> = emptyList(),
    val optimalTime: String? = null,
    val optimalSpace: String? = null,
    val practice: Boolean = false,
    val judgedMs: Int = 0,
    val rank: Int? = null,
    val total: Int? = null,
    val score: Int? = null,
    val streak: Int? = null,
    /** Set when the call never reached a verdict: offline, refused, rate limited. */
    val error: String? = null,
) {
    val accepted: Boolean get() = verdict == "accepted" && error == null
}

/** The outcome of one local sample against the device run. */
data class UiSampleOutcome(
    val idx: Int,
    val passed: Boolean,
    val expectedJson: String,
    val actualJson: String?,
    val error: String? = null,
)

/**
 * The result of running the published samples on the device.
 *
 * This decides nothing and is never sent anywhere. It exists so a solver gets an answer in a second or
 * two instead of waiting on a server round trip for every idea they try.
 */
data class UiLocalRun(
    val compiled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
    val outcomes: List<UiSampleOutcome> = emptyList(),
    val elapsedMs: Long = 0,
) {
    val passed: Boolean get() = compiled && outcomes.isNotEmpty() && outcomes.all { it.passed }
}

interface ChallengeService {
    fun challengesAvailable(): Boolean = false

    /** Today's challenge, or a past one. Works signed out; the progress fields are then empty. */
    suspend fun day(date: String? = null): UiChallengeDay = UiChallengeDay()

    /** Starts the solve clock on the server. A second call does not restart it. */
    suspend fun start(date: String? = null): Boolean = false

    suspend fun board(date: String? = null, limit: Int = 50): UiChallengeBoard = UiChallengeBoard()

    suspend fun profile(): UiChallengeProfile = UiChallengeProfile()

    /**
     * Compiles and runs [source] against the published samples on this device.
     *
     * Fast, unscored, and entirely local. The samples are public, so nothing is revealed by checking
     * them here.
     */
    suspend fun run(
        language: UiChallengeLanguage,
        source: String,
        problem: UiChallengeProblem,
    ): UiLocalRun = UiLocalRun()

    /** Sends the solution for judging. The server decides; this call only reports what it decided. */
    suspend fun submit(date: String, language: UiChallengeLanguage, source: String): UiChallengeVerdict =
        UiChallengeVerdict(error = "Challenges are not available in this build")

    /** Code completion inside the challenge editor, against a hidden scratch project. */
    suspend fun complete(language: UiChallengeLanguage, code: String, offset: Int): UiCompletionResult =
        UiCompletionResult(emptyList(), offset, offset)

    suspend fun analyze(language: UiChallengeLanguage, code: String): List<UiDiagnostic> = emptyList()

    suspend fun hints(language: UiChallengeLanguage, code: String, startOffset: Int, endOffset: Int): List<UiInlayHint> =
        emptyList()

    suspend fun folds(language: UiChallengeLanguage, code: String): List<UiFoldRegion> = emptyList()

    /** Creates the scratch project and waits for its index, so the editor is useful from the first keystroke. */
    suspend fun prepare(language: UiChallengeLanguage): Boolean = true

    suspend fun indexing(language: UiChallengeLanguage): Boolean = false

    object Unsupported : ChallengeService
}
