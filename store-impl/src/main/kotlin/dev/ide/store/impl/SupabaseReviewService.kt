package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.RemoteReview
import dev.ide.store.RemoteReviewPage
import dev.ide.store.ReviewSort
import dev.ide.store.StoreResult
import dev.ide.store.StoreReviewService
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ratings and reviews over PostgREST.
 *
 * Reads go out with the publishable key alone, so the panel draws for a signed-out reader; when there IS a
 * session the same call additionally returns the caller's own review and which reviews they have voted on,
 * which is why the token is attached when available rather than only on writes.
 *
 * Writes are plain RPCs that run as the caller, so `store_ratings`' policies decide what is permitted. A
 * 401 back from one of them means "not signed in", which the UI turns into a sign-in prompt rather than an
 * error.
 */
class SupabaseReviewService(
    url: String,
    private val apiKey: String,
    private val accounts: SupabaseAccountService? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : StoreReviewService {

    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun reviewsAvailable(): Boolean = configured

    override fun reviews(
        itemSlug: String,
        sort: ReviewSort,
        limit: Int,
        offset: Int,
    ): StoreResult<RemoteReviewPage> {
        val body = buildString {
            append('{')
            append(""""p_slug":""").append(jsonStr(itemSlug)).append(',')
            append(""""p_sort":""").append(jsonStr(sort.wire)).append(',')
            append(""""p_limit":""").append(limit).append(',')
            append(""""p_offset":""").append(offset)
            append('}')
        }
        return when (val r = rpc("store_item_reviews", body)) {
            is StoreResult.Ok -> {
                val root = JsonReader.parseOrNull(r.value)
                    ?: return StoreResult.Failed("Reviews response was not valid JSON")
                StoreResult.Ok(parsePage(root))
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    override fun rate(
        itemSlug: String,
        stars: Int,
        review: String?,
        appVersion: String?,
        itemVersion: String?,
    ): StoreResult<Unit> {
        val body = buildString {
            append('{')
            append(""""p_slug":""").append(jsonStr(itemSlug)).append(',')
            append(""""p_stars":""").append(stars).append(',')
            append(""""p_review":""").append(review?.let { jsonStr(it) } ?: "null").append(',')
            append(""""p_app_version":""").append(appVersion?.let { jsonStr(it) } ?: "null").append(',')
            append(""""p_item_version":""").append(itemVersion?.let { jsonStr(it) } ?: "null")
            append('}')
        }
        return unit(rpc("store_rate_item", body))
    }

    override fun deleteMyReview(itemSlug: String): StoreResult<Unit> =
        unit(rpc("store_delete_my_review", """{"p_slug":${jsonStr(itemSlug)}}"""))

    override fun vote(itemSlug: String, authorId: String, helpful: Boolean): StoreResult<Unit> =
        unit(
            rpc(
                "store_vote_review",
                """{"p_slug":${jsonStr(itemSlug)},"p_author":${jsonStr(authorId)},"p_helpful":$helpful}""",
            ),
        )

    private fun unit(result: StoreResult<String>): StoreResult<Unit> = when (result) {
        is StoreResult.Ok -> StoreResult.Ok(Unit)
        is StoreResult.Unavailable -> StoreResult.Unavailable(result.reason)
        is StoreResult.Failed -> StoreResult.Failed(result.message, result.status)
    }

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
                // The session when there is one, the publishable key otherwise. Reads work either way; the
                // difference is whether the response can say "this is your review" and "you voted on this".
                setRequestProperty("Authorization", "Bearer ${accounts?.bearer() ?: apiKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 -> StoreResult.Ok(text)
                code == 401 || code == 403 -> StoreResult.Failed("Sign in to do that", code)
                code == 429 || code >= 500 -> StoreResult.Unavailable("Store unavailable (HTTP $code)")
                else -> StoreResult.Failed(errorMessage(text) ?: "Store rejected the request", code)
            }
        } catch (e: Exception) {
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
    }

    private fun errorMessage(body: String): String? =
        JsonReader.parseOrNull(body)?.let { JsonReader.str(it, "message") }?.takeIf { it.isNotBlank() }

    companion object {
        internal fun parsePage(root: Any?): RemoteReviewPage {
            val avg = JsonReader.obj(root)?.get("average")
            return RemoteReviewPage(
                // Absent rather than zero when nothing is rated: a project with no reviews has no average,
                // and showing 0.0 would read as unanimously terrible.
                average = when (avg) {
                    is Number -> avg.toFloat()
                    is String -> avg.toFloatOrNull() ?: -1f
                    else -> -1f
                },
                count = JsonReader.int(root, "count", 0),
                distribution = parseDistribution(JsonReader.obj(root)?.get("distribution")),
                mine = JsonReader.obj(root)?.get("mine")?.let { parseReview(it)?.copy(mine = true) },
                reviews = JsonReader.arr(JsonReader.obj(root)?.get("reviews")).mapNotNull { parseReview(it) },
            )
        }

        private fun parseDistribution(value: Any?): Map<Int, Int> {
            val obj = JsonReader.obj(value) ?: return emptyMap()
            return obj.entries.mapNotNull { (k, v) ->
                val star = k.toIntOrNull() ?: return@mapNotNull null
                val n = (v as? Number)?.toInt() ?: (v as? String)?.toIntOrNull() ?: return@mapNotNull null
                star to n
            }.toMap()
        }

        internal fun parseReview(value: Any?): RemoteReview? {
            val author = JsonReader.str(value, "authorId") ?: return null
            val stars = JsonReader.int(value, "stars", 0)
            if (stars < 1) return null
            return RemoteReview(
                authorId = author,
                authorName = JsonReader.str(value, "authorName"),
                authorHandle = JsonReader.str(value, "authorHandle"),
                verified = JsonReader.bool(value, "verified", false),
                stars = stars,
                review = JsonReader.str(value, "review"),
                helpful = JsonReader.int(value, "helpful", 0),
                votedByMe = JsonReader.bool(value, "votedByMe", false),
                appVersion = JsonReader.str(value, "appVersion"),
                itemVersion = JsonReader.str(value, "itemVersion"),
                createdAt = JsonReader.str(value, "createdAt"),
                updatedAt = JsonReader.str(value, "updatedAt"),
                reply = JsonReader.str(value, "reply"),
                mine = JsonReader.bool(value, "mine", false),
            )
        }

        private fun jsonStr(value: String): String = buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }

    private fun jsonStr(value: String): String = Companion.jsonStr(value)
}
