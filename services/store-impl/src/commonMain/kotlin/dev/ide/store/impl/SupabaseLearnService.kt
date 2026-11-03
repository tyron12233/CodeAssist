package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.store.LearnAccountState
import dev.ide.store.LearnBadge
import dev.ide.store.LearnCatalogPayload
import dev.ide.store.LearnPublicProfile
import dev.ide.store.LearnStepRecord
import dev.ide.store.LearnStreak
import dev.ide.store.LearnRemoteService
import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreHttp

/**
 * Learn over Supabase RPCs (the `learn_` migrations under `supabase/migrations`). The catalog read is anonymous-safe: it
 * goes with the user's token when signed in and the publishable key otherwise, like the store's browse.
 *
 * Takes the account service now, although the catalog does not need a session, because account progress
 * and lesson discussions will join this client and they do.
 */
class SupabaseLearnService(
    url: String,
    private val apiKey: String,
    private val accounts: SupabaseAccountService?,
    connectTimeoutMs: Int = 10_000,
    /** The catalog is a couple of hundred KB, so allow longer than a small RPC. */
    readTimeoutMs: Int = 30_000,
) : LearnRemoteService {

    private val http = StoreHttp(connectTimeoutMs, readTimeoutMs)
    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun learnRemoteAvailable(): Boolean = configured

    override fun latestCatalog(sinceVersion: Long, format: Int): StoreResult<LearnCatalogPayload?> {
        val res = rpc("learn_catalog_latest", JsonWriter.obj("p_since" to sinceVersion, "p_format" to format))
        val root = when (res) {
            is StoreResult.Ok -> res.value ?: return StoreResult.Ok(null)
            is StoreResult.Unavailable -> return StoreResult.Unavailable(res.reason)
            is StoreResult.Failed -> return StoreResult.Failed(res.message, res.status)
        }
        val body = JsonReader.str(root, "body").orEmpty()
        val version = JsonReader.long(root, "version")
        if (body.isBlank() || version <= 0L) return StoreResult.Ok(null)
        return StoreResult.Ok(
            LearnCatalogPayload(
                version = version,
                format = JsonReader.int(root, "format").takeIf { it > 0 } ?: 1,
                sha256 = JsonReader.str(root, "sha256").orEmpty(),
                body = body,
            ),
        )
    }

    override fun accountId(): String? = accounts?.current()?.userId

    override fun syncProgress(
        steps: List<LearnStepRecord>,
        completedTracks: List<String>,
        timeZone: String,
    ): StoreResult<LearnAccountState> {
        if (accounts?.bearer() == null) return StoreResult.Failed("Sign in to sync progress", 401)
        val body = JsonWriter.obj(
            "p_steps" to steps.map { s ->
                if (s.completedAtMs != null) mapOf("l" to s.lessonId, "s" to s.stepId, "at" to s.completedAtMs)
                else mapOf("l" to s.lessonId, "s" to s.stepId)
            },
            "p_tracks" to completedTracks,
            "p_tz" to timeZone,
        )
        return when (val res = rpc("learn_sync", body)) {
            is StoreResult.Ok -> StoreResult.Ok(parseAccount(res.value))
            is StoreResult.Unavailable -> StoreResult.Unavailable(res.reason)
            is StoreResult.Failed -> StoreResult.Failed(res.message, res.status)
        }
    }

    override fun publicProfile(handle: String): StoreResult<LearnPublicProfile?> =
        when (val res = rpc("learn_public_profile", JsonWriter.obj("p_handle" to handle))) {
            is StoreResult.Ok -> StoreResult.Ok(
                res.value?.let { root ->
                    LearnPublicProfile(
                        badges = parseBadges(JsonReader.obj(root)?.get("badges")),
                        streak = parseStreak(JsonReader.obj(root)?.get("streak")),
                        lessonsStarted = JsonReader.int(root, "lessons"),
                    )
                },
            )
            is StoreResult.Unavailable -> StoreResult.Unavailable(res.reason)
            is StoreResult.Failed -> StoreResult.Failed(res.message, res.status)
        }

    private fun parseAccount(root: Any?): LearnAccountState {
        val o = JsonReader.obj(root) ?: return LearnAccountState()
        val steps = (o["steps"] as? List<*>).orEmpty().mapNotNull { pair ->
            val p = pair as? List<*> ?: return@mapNotNull null
            val l = p.getOrNull(0) as? String ?: return@mapNotNull null
            val s = p.getOrNull(1) as? String ?: return@mapNotNull null
            l to s
        }.toSet()
        return LearnAccountState(steps, parseBadges(o["badges"]), parseStreak(o["streak"]))
    }

    private fun parseBadges(v: Any?): List<LearnBadge> = (v as? List<*>).orEmpty().mapNotNull { b ->
        val track = JsonReader.str(b, "track") ?: return@mapNotNull null
        LearnBadge(track, JsonReader.str(b, "at").orEmpty())
    }

    private fun parseStreak(v: Any?) = LearnStreak(
        current = JsonReader.int(v, "current"),
        best = JsonReader.int(v, "best"),
        activeToday = JsonReader.bool(v, "activeToday"),
    )

    /** POST an RPC with the user's token when signed in (refreshing once on a 401), else the publishable key. */
    private fun rpc(name: String, body: String): StoreResult<Any?> {
        if (!configured) return StoreResult.Unavailable("Learn is not connected in this build")
        val token = accounts?.bearer()
        val first = send(name, body, token)
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
                "Authorization" to "Bearer ${token ?: apiKey}",
            ),
            body = body,
        )
        if (r.error != null) return StoreResult.Unavailable(r.error)
        if (r.status !in 200..299) return StoreResult.Failed("$name failed", r.status)
        return StoreResult.Ok(JsonReader.parseOrNull(r.body))
    }
}
