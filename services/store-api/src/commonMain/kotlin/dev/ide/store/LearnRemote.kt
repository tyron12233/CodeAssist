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
    /** Whether passing solutions are shared with other learners (on unless the learner turned it off). */
    val shareSolutions: Boolean = true,
)

/** What anyone can see on a profile page: badges, the streak, and how many lessons were started. */
data class LearnPublicProfile(
    val badges: List<LearnBadge> = emptyList(),
    val streak: LearnStreak = LearnStreak(),
    val lessonsStarted: Int = 0,
)

// ---- community: lesson discussions and shared solutions ----

/** Who wrote something: the public profile's handle (null when they have none), display name and avatar. */
data class LearnAuthor(val handle: String? = null, val name: String = "Learner", val avatarUrl: String? = null)

/** A question in a lesson's discussion, as listed. */
data class LearnThreadSummary(
    val id: String,
    val lessonId: String,
    val stepId: String?,
    val title: String,
    val author: LearnAuthor,
    val answers: Int,
    val accepted: Boolean,
    val hasCode: Boolean,
    val createdAt: String,
    val lastActivityAt: String,
    val mine: Boolean,
)

/** A question with everything it carries: the asker's code and the error they hit, if they attached them. */
data class LearnThread(
    val id: String,
    val lessonId: String,
    val stepId: String?,
    val title: String,
    val body: String,
    val code: String?,
    val language: String?,
    val errorText: String?,
    val author: LearnAuthor,
    val createdAt: String,
    val mine: Boolean,
    val acceptedPost: String?,
)

/** An answer. [votedByMe] is the caller's own upvote. */
data class LearnPost(
    val id: String,
    val body: String,
    val code: String?,
    val author: LearnAuthor,
    val score: Int,
    val votedByMe: Boolean,
    val mine: Boolean,
    val accepted: Boolean,
    val createdAt: String,
)

data class LearnThreadDetail(val thread: LearnThread, val posts: List<LearnPost>, val canModerate: Boolean)

/** A learner's passing solution to one exercise. */
data class LearnSolution(
    val id: String,
    val code: String,
    val language: String,
    val author: LearnAuthor,
    val likes: Int,
    val likedByMe: Boolean,
    val mine: Boolean,
    val createdAt: String,
)

/** Solutions to one exercise. [unlocked] false (and an empty list) until the caller has passed it. */
data class LearnSolutionPage(val unlocked: Boolean, val solutions: List<LearnSolution>)

/** One reported piece of content waiting for a moderator, with how many reports it has. */
data class LearnReportItem(
    val id: String,
    val kind: String,
    val targetId: String,
    val reason: String,
    val detail: String?,
    val reports: Int,
    val excerpt: String,
    val authorHandle: String?,
    val lessonId: String?,
    val stepId: String?,
    val hidden: Boolean,
    val createdAt: String,
)

/**
 * Lesson discussions, shared solutions and their moderation. Reads work signed out; writes need an account
 * and are rate-limited and moderated on the server. Kept separate from [LearnRemoteService] so a backend
 * can offer the catalog and progress without the community, and so fakes stay small.
 */
interface LearnCommunityService {
    fun communityAvailable(): Boolean = false

    fun threads(lessonId: String, stepId: String? = null, limit: Int = 30, offset: Int = 0): StoreResult<List<LearnThreadSummary>> = unavailable()
    fun threadCount(lessonId: String): StoreResult<Int> = unavailable()
    fun thread(threadId: String): StoreResult<LearnThreadDetail?> = unavailable()
    fun createThread(
        lessonId: String,
        stepId: String?,
        title: String,
        body: String,
        code: String?,
        language: String?,
        errorText: String?,
    ): StoreResult<String> = unavailable()
    fun reply(threadId: String, body: String, code: String?): StoreResult<String> = unavailable()
    fun vote(postId: String, up: Boolean): StoreResult<Int> = unavailable()
    fun accept(threadId: String, postId: String?): StoreResult<Unit> = unavailable()
    fun deleteMine(kind: String, id: String): StoreResult<Unit> = unavailable()
    fun report(kind: String, id: String, reason: String, detail: String?): StoreResult<Unit> = unavailable()

    /** Share a passing solution. `Ok(false)` when the account has opted out of sharing (nothing is stored). */
    fun submitSolution(lessonId: String, stepId: String, code: String, language: String): StoreResult<Boolean> = unavailable()
    fun solutions(lessonId: String, stepId: String, sort: String = "top", limit: Int = 30, offset: Int = 0): StoreResult<LearnSolutionPage> = unavailable()
    fun likeSolution(solutionId: String, like: Boolean): StoreResult<Int> = unavailable()
    fun setShareSolutions(share: Boolean): StoreResult<Unit> = unavailable()

    fun reportQueue(limit: Int = 50): StoreResult<List<LearnReportItem>> = unavailable()
    fun resolveReport(reportId: String, hide: Boolean): StoreResult<Unit> = unavailable()

    companion object {
        val Unsupported: LearnCommunityService = object : LearnCommunityService {}
        private fun <T> unavailable(): StoreResult<T> = StoreResult.Unavailable("Learn discussions are not connected in this build")
    }
}
