package dev.ide.store

/**
 * The online half of Learn. Today it delivers the lesson catalog; account progress and lesson discussions
 * join it in later phases.
 *
 * Reads work signed out. Nothing here is required for Learn to work: the app always has its bundled
 * catalog, so every failure degrades to "keep showing what we have".
 */
interface LearnRemoteService {
    /** Whether a backend is configured at all. */
    fun learnRemoteAvailable(): Boolean = false

    /**
     * The newest published catalog above [sinceVersion] that a client reading envelope [format] can parse.
     * `Ok(null)` means the client is already current.
     */
    fun latestCatalog(sinceVersion: Long, format: Int): StoreResult<LearnCatalogPayload?> =
        StoreResult.Unavailable("Learn is not connected in this build")

    /** The signed-in account's id, or null when signed out. Progress syncs only for an account. */
    fun accountId(): String? = null

    /**
     * Upload [steps] and the [completedTracks] this device knows, and get back the account's whole state.
     * The server keeps the union, so a sync never removes anything. [timeZone] is an IANA id; streak days
     * are counted in it.
     */
    fun syncProgress(
        steps: List<LearnStepRecord>,
        completedTracks: List<String>,
        timeZone: String,
    ): StoreResult<LearnAccountState> = StoreResult.Unavailable("Learn is not connected in this build")

    /** The Learn side of a public profile, by handle. `Ok(null)` when that person has not learned anything. */
    fun publicProfile(handle: String): StoreResult<LearnPublicProfile?> =
        StoreResult.Unavailable("Learn is not connected in this build")

    companion object {
        val Unsupported: LearnRemoteService = object : LearnRemoteService {}
    }
}

/** A published catalog as the server returned it. [body] is the catalog JSON, untouched. */
data class LearnCatalogPayload(
    val version: Long,
    val format: Int,
    val sha256: String,
    val body: String,
)

/**
 * One completed step to upload. [completedAtMs] set means it was just completed on this device and counts
 * toward the day streak; null means imported history (say, progress made before signing in), which does not.
 */
data class LearnStepRecord(val lessonId: String, val stepId: String, val completedAtMs: Long? = null)

/** A track the account has finished, and when the server first heard of it (ISO-8601). */
data class LearnBadge(val trackId: String, val earnedAt: String = "")

/** Day streaks in the learner's own time zone. [activeToday] is whether today already counts. */
data class LearnStreak(val current: Int = 0, val best: Int = 0, val activeToday: Boolean = false)

/** Everything the account holds: every completed (lesson, step), the badges, and the streak. */
data class LearnAccountState(
    val steps: Set<Pair<String, String>> = emptySet(),
    val badges: List<LearnBadge> = emptyList(),
    val streak: LearnStreak = LearnStreak(),
)

/** What anyone can see on a profile page: badges, the streak, and how many lessons were started. */
data class LearnPublicProfile(
    val badges: List<LearnBadge> = emptyList(),
    val streak: LearnStreak = LearnStreak(),
    val lessonsStarted: Int = 0,
)
