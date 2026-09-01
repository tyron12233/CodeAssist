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
import java.net.HttpURLConnection
import java.net.URL

/**
 * The catalog transport: Supabase PostgREST over `java.net.HttpURLConnection`.
 *
 * Stdlib only, deliberately — the same path the analytics sink and the dependency resolver take, because
 * it exists on both the JVM and ART with no extra dependency and no shading.
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
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : StoreCatalogSource {

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
        into: java.io.File,
        onProgress: (Float) -> Unit,
    ): StoreResult<Unit> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        return try {
            val url = "$base/storage/v1/object/public/store-payloads/$storagePath"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("apikey", apiKey)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
                return if (code == 429 || code >= 500) {
                    StoreResult.Unavailable("Download unavailable (HTTP $code)")
                } else {
                    StoreResult.Failed("The project could not be downloaded (HTTP $code)", code)
                }
            }
            val total = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var read = 0L
            into.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                into.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toDouble() / total).coerceIn(0.0, 1.0).toFloat())
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
                into.delete()
                return StoreResult.Failed("The download did not match its checksum")
            }
            onProgress(1f)
            StoreResult.Ok(Unit)
        } catch (e: Exception) {
            into.delete()
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
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

    /** GET a PostgREST path. Same error mapping as [rpc]: a 5xx reads as offline, not as a bad request. */
    private fun get(path: String): StoreResult<String> {
        return try {
            val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 -> StoreResult.Ok(text)
                code == 429 || code >= 500 -> StoreResult.Unavailable("Store unavailable (HTTP $code)")
                else -> StoreResult.Failed(errorMessage(text) ?: "Store rejected the request", code)
            }
        } catch (e: Exception) {
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
    }

    /** POST to a PostgREST RPC endpoint, returning the raw response body. */
    private fun rpc(name: String, body: String): StoreResult<String> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        return try {
            val conn = (URL("$base/rest/v1/rpc/$name").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // Drain either stream so the socket returns to the keep-alive pool; never disconnect().
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 -> StoreResult.Ok(text)
                // A 5xx or a rate limit is the server having a bad day, not the catalog being wrong:
                // treat it like offline so the caller falls back to the bundled catalog quietly.
                code == 429 || code >= 500 -> StoreResult.Unavailable("Store unavailable (HTTP $code)")
                else -> StoreResult.Failed(errorMessage(text) ?: "Store rejected the request", code)
            }
        } catch (e: Exception) {
            // No network is the ordinary case on a phone, so this is Unavailable, not Failed.
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
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
            )
        }

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
