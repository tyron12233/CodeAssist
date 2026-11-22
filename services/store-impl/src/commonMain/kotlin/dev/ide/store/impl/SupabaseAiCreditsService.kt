package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.AiCreditsService
import dev.ide.store.AiUsage
import dev.ide.store.AiUsageModel
import dev.ide.store.AiUsageStep
import dev.ide.store.AiWallet
import dev.ide.store.AiWalletModel
import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreHttp

/**
 * Whoever owns the Supabase session the free tier rides on: the proxy needs the live access token, and only
 * the session's owner can hand one out (and refresh it before it lapses).
 *
 * Usually that is the [SupabaseAccountService] in the same process. On iOS the engine runs in a native image
 * beside the app, and the session stays in the app (its keychain, its sign-in sheet): the engine asks the app
 * through this rather than keeping a second copy of a refresh token that rotates on every use.
 */
interface AiCreditsSession {
    /** Whether this build can sign in at all. */
    fun authAvailable(): Boolean

    /** A live session, or a stored one to restore. Never the network. */
    fun hasStoredSession(): Boolean

    /** A current access token, refreshed first if the one in hand is spent; null when signed out. */
    fun bearer(): String?

    /** A token to retry with after [stale] was refused; null when there is nothing left to try. */
    fun reauthorize(stale: String?): String?
}

/**
 * [AiCreditsService] over the `ai_my_wallet` RPC and the `ai-proxy` edge function.
 *
 * Takes the session's owner ([AiCreditsSession]), as the daily challenges take the account service, because
 * the proxy needs the live access token.
 */
class SupabaseAiCreditsService(
    url: String,
    private val apiKey: String,
    private val accounts: AiCreditsSession,
    /**
     * The primary proxy (the Cloudflare Worker), or blank to use the Supabase `ai-proxy` function alone. When set,
     * the Supabase function is the fallback.
     */
    private val proxyUrl: String = "",
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 20_000,
) : AiCreditsService {

    /** Over the account service in this process, which owns the session. */
    constructor(
        url: String,
        apiKey: String,
        accounts: SupabaseAccountService,
        proxyUrl: String = "",
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
    ) : this(url, apiKey, accounts.aiCreditsSession(), proxyUrl, connectTimeoutMs, readTimeoutMs)

    private val http = StoreHttp(connectTimeoutMs, readTimeoutMs)
    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun available(): Boolean = configured && accounts.authAvailable()

    private val supabaseProxy = "$base/functions/v1/ai-proxy"

    override fun proxyBaseUrl(): String? = when {
        !configured -> null
        proxyUrl.isNotBlank() -> proxyUrl.trimEnd('/')
        else -> supabaseProxy
    }

    override fun proxyFallbackUrl(): String? = if (configured && proxyUrl.isNotBlank()) supabaseProxy else null

    override fun signedIn(): Boolean = configured && accounts.hasStoredSession()

    override fun accessToken(): String? = if (configured) accounts.bearer() else null

    override fun refreshAccessToken(stale: String?): String? = if (configured) accounts.reauthorize(stale) else null

    override fun wallet(): StoreResult<AiWallet> = call("ai_my_wallet", "Could not read your AI points", ::parseWallet)

    override fun usage(): StoreResult<AiUsage> = call("ai_my_usage", "Could not read your AI usage", ::parseUsage)

    private fun <T> call(rpc: String, failure: String, parse: (Any?) -> T): StoreResult<T> {
        if (!configured) return StoreResult.Unavailable("CodeAssist Free AI is not available in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in to use CodeAssist Free AI", 401)
        val first = send(rpc, failure, token, parse)
        if (first is StoreResult.Failed && first.status == 401) {
            val fresh = accounts.reauthorize(token) ?: return first
            return send(rpc, failure, fresh, parse)
        }
        return first
    }

    private fun <T> send(rpc: String, failure: String, token: String, parse: (Any?) -> T): StoreResult<T> {
        val r = http.request(
            method = "POST",
            url = "$base/rest/v1/rpc/$rpc",
            headers = mapOf(
                "Content-Type" to "application/json",
                "apikey" to apiKey,
                "Authorization" to "Bearer $token",
            ),
            body = "{}",
        )
        if (r.error != null) return StoreResult.Unavailable(r.error)
        if (r.status !in 200..299) return StoreResult.Failed(failure, r.status)
        val root = JsonReader.parseOrNull(r.body) ?: return StoreResult.Failed(failure)
        return StoreResult.Ok(parse(root))
    }

    internal companion object {
        private fun SupabaseAccountService.aiCreditsSession(): AiCreditsSession {
            val owner = this
            return object : AiCreditsSession {
                override fun authAvailable(): Boolean = owner.authAvailable()
                override fun hasStoredSession(): Boolean = owner.hasStoredSession()
                override fun bearer(): String? = owner.bearer()
                override fun reauthorize(stale: String?): String? = owner.reauthorize(stale)
            }
        }

        private fun dbl(v: Any?, key: String): Double = JsonReader.float(v, key)?.toDouble() ?: 0.0

        fun parseUsage(root: Any?): AiUsage = AiUsage(
            points = dbl(root, "points"),
            freePointsLeft = dbl(root, "freePointsLeft"),
            dailyFreePoints = JsonReader.int(root, "dailyFreePoints"),
            dailyCapPoints = JsonReader.int(root, "dailyCapPoints"),
            resetsAtMs = JsonReader.long(root, "resetsAtMs"),
            adPoints = JsonReader.int(root, "adPoints"),
            adsToday = JsonReader.int(root, "adsToday"),
            adDailyCap = JsonReader.int(root, "adDailyCap"),
            spentToday = dbl(root, "spentToday"),
            stepsToday = JsonReader.int(root, "stepsToday"),
            spentWeek = dbl(root, "spentWeek"),
            stepsWeek = JsonReader.int(root, "stepsWeek"),
            byModelWeek = JsonReader.arr(JsonReader.obj(root)?.get("byModelWeek")).map { m ->
                AiUsageModel(JsonReader.str(m, "model").orEmpty(), dbl(m, "points"), JsonReader.int(m, "steps"))
            },
            recent = JsonReader.arr(JsonReader.obj(root)?.get("recent")).map { s ->
                AiUsageStep(
                    atMs = JsonReader.long(s, "at"),
                    model = JsonReader.str(s, "model").orEmpty(),
                    points = dbl(s, "points"),
                    input = JsonReader.int(s, "input"),
                    output = JsonReader.int(s, "output"),
                    cacheRead = JsonReader.int(s, "cacheRead"),
                    cacheWrite = JsonReader.int(s, "cacheWrite"),
                    webSearches = JsonReader.int(s, "webSearches"),
                )
            },
        )

        fun parseWallet(root: Any?): AiWallet = AiWallet(
            userId = JsonReader.str(root, "userId").orEmpty(),
            enabled = JsonReader.bool(root, "enabled"),
            points = JsonReader.int(root, "points"),
            freePoints = JsonReader.int(root, "freePoints"),
            earnedPoints = JsonReader.int(root, "earnedPoints"),
            dailyFreePoints = JsonReader.int(root, "dailyFreePoints"),
            adPoints = JsonReader.int(root, "adPoints"),
            adsLeftToday = JsonReader.int(root, "adsLeftToday"),
            adDailyCap = JsonReader.int(root, "adDailyCap"),
            premiumAvailable = JsonReader.bool(root, "premiumAvailable"),
            models = JsonReader.arr(JsonReader.obj(root)?.get("models")).map { m ->
                AiWalletModel(
                    id = JsonReader.str(m, "id").orEmpty(),
                    name = JsonReader.str(m, "name").orEmpty(),
                    minPoints = JsonReader.int(m, "minPoints"),
                    premium = JsonReader.bool(m, "premium"),
                    available = JsonReader.bool(m, "available"),
                    pointsPerStep = JsonReader.float(m, "pointsPerStep")?.toDouble() ?: 0.0,
                )
            },
        )
    }
}
