package dev.ide.store

/**
 * CodeAssist Free AI: Claude through the project's own proxy, paid for out of a points wallet.
 *
 * Lives beside the store because it shares everything the store already has: the Supabase project, the
 * GitHub/Google sign-in, and the session that keeps it alive. One point is $0.001 of model usage; a day's
 * free allowance resets at midnight UTC, and rewarded ads top the wallet up.
 *
 * The wallet is read-only from here. Every debit is made by the proxy as a call settles, and every credit
 * for an ad by the server after Google has signed the reward; nothing on the device can add a point.
 */
data class AiWallet(
    /** The Supabase user id. Rewarded ads carry it so Google's signed callback credits the right wallet. */
    val userId: String,
    /** False when the tier is switched off or this month's budget is spent. */
    val enabled: Boolean,
    val points: Int,
    /** What is left of today's allowance (spent first; does not carry over). */
    val freePoints: Int,
    /** Sign-up bonus plus ads (kept). */
    val earnedPoints: Int,
    val dailyFreePoints: Int,
    /** What one watched ad adds. */
    val adPoints: Int,
    val adsLeftToday: Int,
    val adDailyCap: Int,
    /** False once the month's spending has passed the point where premium models switch off. */
    val premiumAvailable: Boolean,
    val models: List<AiWalletModel> = emptyList(),
)

data class AiWalletModel(
    val id: String,
    val name: String,
    /** The balance a call on this model needs before it may start. */
    val minPoints: Int,
    val premium: Boolean,
    val available: Boolean,
    /**
     * What one step (one request to the model) costs in points: the last week's measured average, or the server's
     * estimate before there is enough usage to measure. A reply that uses tools takes several steps.
     */
    val pointsPerStep: Double = 0.0,
)

/** The signed-in user's own usage: today, the last 7 days, and the most recent steps. Points to one decimal. */
data class AiUsage(
    val points: Double,
    val freePointsLeft: Double,
    val dailyFreePoints: Int,
    /** The most one account may spend in a UTC day, in points, whatever its balance. */
    val dailyCapPoints: Int,
    /** When today's allowance and the daily cap reset (next midnight UTC), epoch milliseconds. */
    val resetsAtMs: Long,
    val adPoints: Int,
    val adsToday: Int,
    val adDailyCap: Int,
    val spentToday: Double,
    val stepsToday: Int,
    val spentWeek: Double,
    val stepsWeek: Int,
    val byModelWeek: List<AiUsageModel> = emptyList(),
    val recent: List<AiUsageStep> = emptyList(),
)

data class AiUsageModel(val model: String, val points: Double, val steps: Int)

/** One request to the model and what it cost. */
data class AiUsageStep(
    val atMs: Long,
    val model: String,
    val points: Double,
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    val webSearches: Int,
)

/** The port. [Unsupported] on a build with no Supabase config (and on hosts that never wire it). */
interface AiCreditsService {
    /** Whether this build can reach the free tier at all. */
    fun available(): Boolean = false

    /**
     * The proxy's root, which the app's Anthropic client treats as its API base URL
     * (it appends `/v1/messages`).
     */
    fun proxyBaseUrl(): String? = null

    /** A second proxy to try when [proxyBaseUrl] cannot be reached or fails before answering; null for none. */
    fun proxyFallbackUrl(): String? = null

    /** Whether a session is live or can be restored, without a network call. */
    fun signedIn(): Boolean = false

    /**
     * A current access token for the proxy, refreshed when the one in hand is about to expire. May go to the
     * network; call it off the main thread. Null when signed out.
     */
    fun accessToken(): String? = null

    /** Trade the refresh token in after [stale] was refused; null when there is nothing left to try. */
    fun refreshAccessToken(stale: String?): String? = null

    /** The signed-in caller's wallet. Creates it, with its sign-up bonus, on the first call. */
    fun wallet(): StoreResult<AiWallet> = StoreResult.Unavailable("CodeAssist Free AI is not available in this build")

    /** The signed-in caller's usage summary. */
    fun usage(): StoreResult<AiUsage> = StoreResult.Unavailable("CodeAssist Free AI is not available in this build")

    companion object {
        val Unsupported: AiCreditsService = object : AiCreditsService {}
    }
}
