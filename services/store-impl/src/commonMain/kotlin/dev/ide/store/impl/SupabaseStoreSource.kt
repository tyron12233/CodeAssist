package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteCategory
import dev.ide.store.RemoteItemKind
import dev.ide.store.RemoteSection
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.store.impl.platform.StoreFs
import dev.ide.store.impl.platform.StoreHttp

/**
 * The catalog transport: Supabase PostgREST over [StoreHttp].
 *
 * Which socket that is, is the platform's business (`HttpURLConnection` on the JVM and ART,
 * `NSURLSession` on iOS); everything in this file — the RPC names, the bodies, and which status means
 * offline rather than rejected — is the same wherever it runs.
 *
 * Two RPCs carry the whole read side: `store_catalog(p_app_build)` returns the entire Explore screen as
 * one document, and `store_search(...)` returns a ranked list. Both are anonymous — the publishable key
 * is all this needs, and row-level security is what confines the response to approved rows. See
 * `supabase/migrations/20260901000500_store_api.sql`.
 */
class SupabaseStoreSource(
    url: String,
    private val apiKey: String,
    /** This installation's build number; null disables build filtering. */
    override val appBuild: Int? = null,
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 20_000,
) : StoreCatalogSource {

    private val http = StoreHttp(connectTimeoutMs, readTimeoutMs)
    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun configured(): Boolean = configured

    override fun catalog(appBuild: Int): StoreResult<RemoteCatalog> {
        val body = """{"p_app_build":$appBuild}"""
        return when (val r = rpc("store_catalog", body)) {
            is StoreResult.Ok -> {
                val parsed = JsonReader.parseOrNull(r.value)
                    ?: return StoreResult.Failed("Catalog response was not valid JSON")
                StoreResult.Ok(parseCatalog(parsed))
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    override fun search(query: StoreQuery, appBuild: Int): StoreResult<List<RemoteStoreItem>> {
        val body = buildString {
            append('{')
            append(""""p_query":""").append(jsonStr(query.text)).append(',')
            append(""""p_category":""").append(query.category?.let { jsonStr(it) } ?: "null").append(',')
            append(""""p_kind":""").append(query.kind?.let { jsonStr(it.name.lowercase()) } ?: "null").append(',')
            append(""""p_min_rating":""").append(query.minRating).append(',')
            append(""""p_sort":""").append(jsonStr(query.sort.wire)).append(',')
            append(""""p_limit":""").append(query.limit).append(',')
            append(""""p_offset":""").append(query.offset).append(',')
            append(""""p_app_build":""").append(appBuild)
            append('}')
        }
        return when (val r = rpc("store_search", body)) {
            is StoreResult.Ok -> {
                val parsed = JsonReader.parseOrNull(r.value)
                    ?: return StoreResult.Failed("Search response was not valid JSON")
                StoreResult.Ok(JsonReader.arr(parsed).mapNotNull(::parseItem))
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    /**
     * The Explore feed document, straight from `store_explore()`.
     *
     * Returned unparsed so the caller can cache the exact bytes it renders.
     */
    override fun feedDocument(seedSlug: String?): StoreResult<String> {
        val body = buildString {
            append("""{"p_app_build":""").append(appBuild?.toString() ?: "null")
            if (seedSlug != null) append(""","p_seed_slug":""").append(jsonStr(seedSlug))
            append('}')
        }
        return rpc("store_explore", body)
    }

    /**
     * Stream an approved payload out of the public `store-payloads` bucket.
     *
     * Public means a plain unauthenticated GET, which is why the bucket exists: no signing round trip and
     * the CDN can cache it. The publishable key is still sent so the request is attributable.
     *
     * Hashed **while streaming**, so a 5 MB archive is never held in memory twice and the verification
     * costs no second pass. A hash mismatch deletes the file and fails: an unverified zip must not reach
     * the extractor.
     */
    override fun downloadPayload(
        storagePath: String,
        expectedSha256: String?,
        expectedBytes: Long,
        intoPath: String,
        onProgress: (Float) -> Unit,
    ): StoreResult<Unit> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        val url = "$base/storage/v1/object/public/store-payloads/$storagePath"
        val reply = http.download(
            url = url,
            headers = mapOf("apikey" to apiKey),
            intoPath = intoPath,
            expectedBytes = expectedBytes,
            hash = expectedSha256 != null,
            onProgress = onProgress,
        )
        reply.error?.let { return StoreResult.Unavailable(it) }
        if (reply.status !in 200..299) {
            return if (reply.status == 429 || reply.status >= 500) {
                StoreResult.Unavailable("Download unavailable (HTTP ${reply.status})")
            } else {
                StoreResult.Failed("The project could not be downloaded (HTTP ${reply.status})", reply.status)
            }
        }
        if (expectedSha256 != null && !expectedSha256.equals(reply.sha256, ignoreCase = true)) {
            StoreFs.delete(intoPath)
            return StoreResult.Failed("The download did not match its checksum")
        }
        onProgress(1f)
        return StoreResult.Ok(Unit)
    }

    override fun downloadMedia(storagePath: String, intoPath: String): StoreResult<Unit> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        val url = "$base/storage/v1/object/public/store-media/$storagePath"
        // Bounded: this writes a remote body to the user's disk, and a screenshot that needs more than
        // this is not one worth caching. Truncated output is deleted rather than left to decode as garbage.
        val reply = http.download(
            url = url,
            headers = mapOf("apikey" to apiKey),
            intoPath = intoPath,
            limitBytes = MAX_MEDIA_BYTES,
        )
        reply.error?.let { return StoreResult.Unavailable(it) }
        if (reply.tooLarge) return StoreResult.Failed("Image is too large", 0)
        if (reply.status !in 200..299) return StoreResult.Failed("Image unavailable (HTTP ${reply.status})", reply.status)
        return StoreResult.Ok(Unit)
    }

    /**
     * Fire-and-forget. A failure here is swallowed on purpose: the count is a nice-to-have and an install
     * must never fail because the counter was unreachable.
     */
    override fun recordInstall(slug: String, installId: String) {
        if (!configured) return
        runCatching {
            rpc("store_record_install", """{"p_slug":${jsonStr(slug)},"p_install_id":${jsonStr(installId)}}""")
        }
    }

    /**
     * The submittable categories, straight off the table.
     *
     * A plain table read rather than an RPC because that is all it is: `store_categories` is granted
     * `select` to anon and its read policy is unconditional, so there is nothing for a function to add.
     */
    override fun categories(): StoreResult<List<Pair<String, String>>> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        return when (val body = get("/rest/v1/store_categories?select=slug,title&order=sort_order")) {
            is StoreResult.Ok -> {
                val rows = JsonReader.arr(JsonReader.parseOrNull(body.value)).mapNotNull { row ->
                    val slug = JsonReader.str(row, "slug") ?: return@mapNotNull null
                    slug to (JsonReader.str(row, "title") ?: slug)
                }
                if (rows.isEmpty()) StoreResult.Unavailable("No categories") else StoreResult.Ok(rows)
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(body.reason)
            is StoreResult.Failed -> StoreResult.Failed(body.message, body.status)
        }
    }

    override fun authProviders(): StoreResult<List<String>> =
        when (val r = rpc("store_auth_providers", "{}")) {
            is StoreResult.Ok -> {
                val names = JsonReader.arr(JsonReader.parseOrNull(r.value)).mapNotNull { it as? String }
                // An empty list would mean "nobody can sign in", which is a real answer the backend is
                // entitled to give (every provider withheld), so it is passed through rather than defaulted.
                StoreResult.Ok(names)
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }

    /** GET a PostgREST path. Same error mapping as [rpc]: a 5xx reads as offline, not as a bad request. */
    private fun get(path: String): StoreResult<String> = reply(
        http.request(
            method = "GET",
            url = "$base$path",
            headers = mapOf("apikey" to apiKey, "Authorization" to "Bearer $apiKey"),
        ),
    )

    /**
     * Register for push. Fire-and-forget in effect: a failure means this launch is not push-reachable,
     * which the next launch retries, and nothing the user is doing should fail because of it.
     */
    override fun registerDevice(
        installId: String,
        token: String,
        platform: String,
        appBuild: Int?,
        topics: List<String>,
    ): StoreResult<Unit> {
        val body = buildString {
            append('{')
            append(""""p_install_id":""").append(jsonStr(installId)).append(',')
            append(""""p_token":""").append(jsonStr(token)).append(',')
            append(""""p_platform":""").append(jsonStr(platform)).append(',')
            append(""""p_app_build":""").append(appBuild?.toString() ?: "null").append(',')
            append(""""p_topics":[""")
            topics.forEachIndexed { i, t -> if (i > 0) append(','); append(jsonStr(t)) }
            append("]}")
        }
        return when (val r = rpc("store_register_device", body)) {
            is StoreResult.Ok -> StoreResult.Ok(Unit)
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    /**
     * Re-register with a new topic list.
     *
     * The same RPC as registration, because the row is keyed by token and the upsert replaces `topics`
     * wholesale — so subscribing and unsubscribing are one call with a different list.
     */
    override fun setTopics(installId: String, token: String, topics: List<String>): StoreResult<Unit> =
        registerDevice(installId = installId, token = token, topics = topics)

    override fun forgetDevice(token: String): StoreResult<Unit> =
        when (val r = rpc("store_forget_device", """{"p_token":${jsonStr(token)}}""")) {
            is StoreResult.Ok -> StoreResult.Ok(Unit)
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }

    /** POST to a PostgREST RPC endpoint, returning the raw response body. */
    private fun rpc(name: String, body: String): StoreResult<String> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        return reply(
            http.request(
                method = "POST",
                url = "$base/rest/v1/rpc/$name",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "apikey" to apiKey,
                    "Authorization" to "Bearer $apiKey",
                ),
                body = body,
            ),
        )
    }

    /** The one status-to-[StoreResult] mapping every read in this file shares. */
    private fun reply(r: dev.ide.store.impl.platform.HttpReply): StoreResult<String> = when {
        // No network is the ordinary case on a phone, so this is Unavailable, not Failed.
        r.error != null -> StoreResult.Unavailable(r.error)
        r.status in 200..299 -> StoreResult.Ok(r.body)
        // A 5xx or a rate limit is the server having a bad day, not the catalog being wrong: treat it like
        // offline so the caller falls back to the bundled catalog quietly.
        r.status == 429 || r.status >= 500 -> StoreResult.Unavailable("Store unavailable (HTTP ${r.status})")
        else -> StoreResult.Failed(errorMessage(r.body) ?: "Store rejected the request", r.status)
    }

    /** PostgREST reports problems as `{"message":…,"details":…}`; surface the message if there is one. */
    private fun errorMessage(body: String): String? =
        JsonReader.parseOrNull(body)?.let { JsonReader.str(it, "message") }?.takeIf { it.isNotBlank() }

    companion object {
        internal fun parseCatalog(root: Any?): RemoteCatalog = RemoteCatalog(
            version = JsonReader.int(root, "version", 1),
            generatedAt = JsonReader.str(root, "generatedAt"),
            categories = JsonReader.arr(JsonReader.obj(root)?.get("categories")).mapNotNull(::parseCategory),
            featured = JsonReader.arr(JsonReader.obj(root)?.get("featured")).mapNotNull(::parseItem),
            sections = JsonReader.arr(JsonReader.obj(root)?.get("sections")).mapNotNull(::parseSection),
        )

        private fun parseCategory(v: Any?): RemoteCategory? {
            val id = JsonReader.str(v, "id") ?: return null
            return RemoteCategory(
                id = id,
                title = JsonReader.str(v, "title") ?: id,
                summary = JsonReader.str(v, "summary"),
                icon = JsonReader.str(v, "icon"),
                color = JsonReader.str(v, "color"),
                count = JsonReader.int(v, "count"),
            )
        }

        private fun parseSection(v: Any?): RemoteSection? {
            val id = JsonReader.str(v, "id") ?: return null
            return RemoteSection(
                id = id,
                title = JsonReader.str(v, "title") ?: id,
                summary = JsonReader.str(v, "summary"),
                items = JsonReader.arr(JsonReader.obj(v)?.get("items")).mapNotNull(::parseItem),
            )
        }

        /**
         * One item. A row without an id or a title is dropped rather than rendered half-blank — the rest
         * of the shelf is still worth showing.
         */
        internal fun parseItem(v: Any?): RemoteStoreItem? {
            val id = JsonReader.str(v, "id") ?: return null
            val title = JsonReader.str(v, "title") ?: return null
            val summary = JsonReader.str(v, "summary").orEmpty()
            return RemoteStoreItem(
                id = id,
                kind = RemoteItemKind.of(JsonReader.str(v, "kind")),
                title = title,
                summary = summary,
                description = JsonReader.str(v, "description") ?: summary,
                blurb = JsonReader.str(v, "blurb"),
                category = JsonReader.str(v, "category").orEmpty(),
                language = JsonReader.str(v, "language"),
                tags = JsonReader.strings(v, "tags"),
                highlights = JsonReader.strings(v, "highlights"),
                accent = JsonReader.str(v, "accent"),
                icon = JsonReader.str(v, "icon"),
                author = JsonReader.str(v, "author"),
                authorHandle = JsonReader.str(v, "authorHandle"),
                verified = JsonReader.bool(v, "verified"),
                templateId = JsonReader.str(v, "templateId"),
                featured = JsonReader.bool(v, "featured"),
                installs = JsonReader.int(v, "installs"),
                likes = JsonReader.int(v, "likes"),
                // Absent (not 0) when nothing is rated — the backend omits the key deliberately.
                rating = JsonReader.float(v, "rating"),
                ratingCount = JsonReader.int(v, "ratingCount"),
                version = JsonReader.str(v, "version"),
                versionCode = JsonReader.int(v, "versionCode"),
                storagePath = JsonReader.str(v, "storagePath"),
                sizeBytes = JsonReader.long(v, "sizeBytes", -1L),
                sha256 = JsonReader.str(v, "sha256"),
                changelog = JsonReader.str(v, "changelog"),
                publishedAt = JsonReader.str(v, "publishedAt"),
                updatedAt = JsonReader.str(v, "updatedAt"),
                screenshots = JsonReader.strings(v, "screenshots"),
                iconPath = JsonReader.str(v, "iconPath"),
            )
        }

        /** A screenshot larger than this is not cached; see `downloadMedia`. */
        private const val MAX_MEDIA_BYTES = 8L * 1024 * 1024

        /** Minimal JSON string escaping — enough for the scalars these RPC bodies carry. */
        internal fun jsonStr(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
