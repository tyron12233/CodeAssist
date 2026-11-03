package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.store.LearnCatalogPayload
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
        if (!configured) return StoreResult.Unavailable("Learn is not connected in this build")
        val r = http.request(
            method = "POST",
            url = "$base/rest/v1/rpc/learn_catalog_latest",
            headers = mapOf(
                "Content-Type" to "application/json",
                "apikey" to apiKey,
                "Authorization" to "Bearer ${accounts?.bearer() ?: apiKey}",
            ),
            body = JsonWriter.obj("p_since" to sinceVersion, "p_format" to format),
        )
        if (r.error != null) return StoreResult.Unavailable(r.error)
        if (r.status !in 200..299) return StoreResult.Failed("learn_catalog_latest failed", r.status)
        val root = JsonReader.parseOrNull(r.body) ?: return StoreResult.Ok(null)
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
}
