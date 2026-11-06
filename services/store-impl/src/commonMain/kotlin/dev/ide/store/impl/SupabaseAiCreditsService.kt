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
 * [AiCreditsService] over the `ai_my_wallet` RPC and the `ai-proxy` edge function.
 *
 * Takes the concrete account service, as the daily challenges do, because the proxy needs the live access
 * token and only the session owner can hand one out (and refresh it before it lapses).
 */
class SupabaseAiCreditsService(
    url: String,
    private val apiKey: String,
    private val accounts: SupabaseAccountService,
    /**
     * The primary proxy (the Cloudflare Worker), or blank to use the Supabase `ai-proxy` function alone. When set,
     * the Supabase function is the fallback.
     */
    private val proxyUrl: String = "",
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 20_000,
) : AiCreditsService {

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
