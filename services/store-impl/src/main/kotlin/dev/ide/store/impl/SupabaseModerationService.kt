package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.log.Log
import dev.ide.store.PendingSubmission
import dev.ide.store.ReportedContent
import dev.ide.store.ReviewQueue
import dev.ide.store.StoreModerationService
import dev.ide.store.StoreResult
import dev.ide.store.SubmissionFile
import dev.ide.store.SubmissionListing
import dev.ide.store.SubmissionSubmitter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Moderation over PostgREST and Storage, as the signed-in moderator.
 *
 * There is no master key here and there is nowhere for one to be: every call carries the account's own
 * session, and what makes that session a moderator's is a row in `store_admins` that no client can read.
 * The functions this calls all re-check `store_is_admin()` in their bodies, so a signed-in stranger gets a
 * 403 from the database rather than being trusted by this class.
 *
 * The one thing the database cannot do is move an object between buckets, because Storage is HTTP. So
 * [approve] does that half here and hands the resulting public path to `store_review_version`, which does
 * everything else — the version flip, the screenshots, the icon and the reviewer stamp — in one
 * transaction. That split is why the order matters and why it is not negotiable: copy, verify, then
 * decide. The reverse publishes a listing whose payload is not there.
 */
class SupabaseModerationService(
    url: String,
    private val apiKey: String,
    private val accounts: SupabaseAccountService,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : StoreModerationService {

    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()
    private val log = Log.logger("StoreModeration")

    override fun moderationAvailable(): Boolean = configured && accounts.authAvailable()

    /**
     * Cached for the life of the session, because it is read to decide whether to draw a surface and that
     * question is asked on every visit to the profile screen. Cleared when the account changes, which is
     * the only thing that can change the answer for a given install.
     */
    private var cachedFor: String? = null
    private var cached: Boolean = false

    override fun amIModerator(): Boolean {
        if (!configured) return false
        val account = accounts.current() ?: run {
            cachedFor = null
            return false
        }
        if (cachedFor == account.userId) return cached
        val token = accounts.bearer() ?: return false
        // A network failure answers false rather than throwing: the caller is deciding whether to show a
        // screen, and "we could not ask" and "no" lead to the same screen.
        val answer = (rpc("store_am_i_admin", "{}", token) as? StoreResult.Ok)
            ?.value?.trim().equals("true", ignoreCase = true)
        cachedFor = account.userId
        cached = answer
        return answer
    }

    override fun queue(limit: Int): StoreResult<ReviewQueue> {
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        return when (val r = rpc("store_review_queue", """{"p_limit":$limit}""", token)) {
            is StoreResult.Ok -> {
                val root = JsonReader.parseOrNull(r.value)
                    ?: return StoreResult.Failed("The review queue was not valid JSON")
                StoreResult.Ok(
                    ReviewQueue(
                        pending = JsonReader.arr(JsonReader.obj(root)?.get("pending")).mapNotNull(::parseSubmission),
                        recent = JsonReader.arr(JsonReader.obj(root)?.get("recent")).mapNotNull(::parseSubmission),
                        pendingCount = JsonReader.int(root, "pendingCount"),
                    ),
                )
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    override fun approve(
        submission: PendingSubmission,
        note: String?,
        clearIconIfMissing: Boolean,
    ): StoreResult<String> {
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        val slug = submission.listing.slug
        // Clean, version-scoped, and free of the submitter's uuid — which the private path begins with and
        // a public URL must not carry. The database refuses a path that still does.
        val publicPath = "$slug/${submission.version}.zip"

        when (val copied = copyObject(UPLOADS, submission.storagePath, PAYLOADS, publicPath, token)) {
            is StoreResult.Ok -> Unit
            is StoreResult.Unavailable -> return StoreResult.Unavailable(copied.reason)
            is StoreResult.Failed -> return StoreResult.Failed(copied.message, copied.status)
        }
        // Proof rather than assumption: an approval pointing at an unreachable object is worse than no
        // approval, because the listing then looks installable and is not. One byte settles it.
        when (val served = headPublicPayload(publicPath)) {
            is StoreResult.Ok -> Unit
            is StoreResult.Unavailable -> return StoreResult.Unavailable(served.reason)
            is StoreResult.Failed -> return StoreResult.Failed(
                "The copied payload is not being served (${served.message})", served.status,
            )
        }

        // Screenshots and the icon are best effort, individually. A published listing must never point at
        // an image that is not there, so only what actually landed is recorded; and one awkward image is
        // not a reason to refuse a project that is otherwise fine.
        //
        // Version-scoped keys, like the payload: the client caches an image by its storage path forever, so
        // v2's screenshots must not land where v1's are already cached on every device.
        val publishedShots = submission.screenshotPaths.mapIndexedNotNull { index, path ->
            val destination = "$slug/${submission.version}/shot-$index.${extensionOf(path)}"
            when (val r = copyObject(UPLOADS, path, MEDIA, destination, token)) {
                is StoreResult.Ok -> destination
                is StoreResult.Unavailable -> { log.warn("Screenshot $index not published: ${r.reason}"); null }
                is StoreResult.Failed -> { log.warn("Screenshot $index not published: ${r.message}"); null }
            }
        }

        var publishedIcon: String? = null
        // A copy that FAILED must keep the listing's current icon: a transient error is not the publisher
        // taking their icon off, and clearing one because of it is a change nobody decided.
        var keepIcon = !clearIconIfMissing
        submission.iconPath?.let { source ->
            val destination = "$slug/${submission.version}/icon.${extensionOf(source)}"
            when (val r = copyObject(UPLOADS, source, MEDIA, destination, token)) {
                is StoreResult.Ok -> { publishedIcon = destination; keepIcon = false }
                is StoreResult.Unavailable -> { log.warn("App icon not published: ${r.reason}"); keepIcon = true }
                is StoreResult.Failed -> { log.warn("App icon not published: ${r.message}"); keepIcon = true }
            }
        }

        val body = buildString {
            append('{')
            append(""""p_version":""").append(q(submission.versionId)).append(',')
            append(""""p_decision":"approve",""")
            append(""""p_note":""").append(note?.takeIf { it.isNotBlank() }?.let { q(it) } ?: "null").append(',')
            append(""""p_storage_path":""").append(q(publicPath)).append(',')
            append(""""p_screenshots":""")
                .append(if (publishedShots.isEmpty()) "null" else publishedShots.joinToString(",", "[", "]") { q(it) })
                .append(',')
            append(""""p_icon_path":""").append(publishedIcon?.let { q(it) } ?: "null").append(',')
            append(""""p_keep_icon":""").append(keepIcon)
            append('}')
        }
        return decision(body, token)
    }

    override fun reject(submission: PendingSubmission, note: String): StoreResult<String> {
        if (note.isBlank()) return StoreResult.Failed("A rejection needs a note the submitter can act on")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        val body = """{"p_version":${q(submission.versionId)},"p_decision":"reject","p_note":${q(note)}}"""
        return decision(body, token)
    }

    /**
     * One decision RPC, and the `{ok, message}` it answers with.
     *
     * A refusal is [StoreResult.Failed] carrying the backend's own sentence: "was already approved" is
     * what a moderator sees when someone else got there first, and it is the useful thing to say.
     */
    private fun decision(body: String, token: String): StoreResult<String> =
        when (val r = rpc("store_review_version", body, token)) {
            is StoreResult.Ok -> {
                val json = JsonReader.parseOrNull(r.value)
                val message = JsonReader.str(json, "message")
                if (JsonReader.bool(json, "ok")) StoreResult.Ok(message ?: "Done")
                else StoreResult.Failed(message ?: "The store refused that decision")
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }

    override fun reports(limit: Int): StoreResult<List<ReportedContent>> {
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        return when (val r = rpc("store_report_queue", """{"p_limit":$limit}""", token)) {
            is StoreResult.Ok -> StoreResult.Ok(
                JsonReader.arr(JsonReader.parseOrNull(r.value)).mapNotNull(::parseReport),
            )
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    override fun setReviewHidden(itemSlug: String, authorId: String, hidden: Boolean): StoreResult<Unit> {
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        val body = """{"p_slug":${q(itemSlug)},"p_author":${q(authorId)},"p_hidden":$hidden}"""
        return unit(rpc("store_set_review_visibility", body, token))
    }

    override fun resolveReport(reportId: String, actioned: Boolean): StoreResult<Unit> {
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        return unit(rpc("store_resolve_report", """{"p_report":${q(reportId)},"p_actioned":$actioned}""", token))
    }

    override fun payloadUrl(submission: PendingSubmission): String? {
        if (!configured) return null
        val bucket = if (submission.status == "approved") PAYLOADS else UPLOADS
        return "$base/storage/v1/object/$bucket/${submission.storagePath}"
    }

    override fun downloadObject(bucket: String, storagePath: String, into: File): StoreResult<Unit> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        return try {
            val conn = open("$base/storage/v1/object/$bucket/$storagePath", "GET", token)
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
                return StoreResult.Failed("Could not read that file (HTTP $code)", code)
            }
            into.parentFile?.mkdirs()
            conn.inputStream.use { input -> into.outputStream().buffered().use { input.copyTo(it) } }
            StoreResult.Ok(Unit)
        } catch (e: Exception) {
            // A half-written file would decode as garbage, so it does not survive the failure.
            into.delete()
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
    }

    // ---- storage ----

    /**
     * Copy one object between buckets.
     *
     * A destination that already exists is replaced rather than refused. That case is a retry after a
     * decision that failed halfway through, and the object sitting there came from this same source — so
     * refusing would make the retry fail at the step least likely to be the problem.
     */
    private fun copyObject(
        fromBucket: String,
        sourceKey: String,
        toBucket: String,
        destinationKey: String,
        token: String,
    ): StoreResult<Unit> {
        val body = buildString {
            append('{')
            append(""""bucketId":""").append(q(fromBucket)).append(',')
            append(""""sourceKey":""").append(q(sourceKey)).append(',')
            append(""""destinationBucket":""").append(q(toBucket)).append(',')
            append(""""destinationKey":""").append(q(destinationKey))
            append('}')
        }
        val first = storagePost("/storage/v1/object/copy", body, token)
        if (first !is StoreResult.Failed || !isDuplicate(first)) return unit(first)
        // Storage answers a taken key as HTTP 400 carrying `"statusCode":"409"`, not as a 409.
        deleteObject(toBucket, destinationKey, token)
        return unit(storagePost("/storage/v1/object/copy", body, token))
    }

    private fun isDuplicate(failure: StoreResult.Failed<String>): Boolean =
        failure.status == 409 ||
            failure.message.contains("409") ||
            failure.message.contains("already exists", ignoreCase = true) ||
            failure.message.contains("Duplicate", ignoreCase = true)

    private fun deleteObject(bucket: String, key: String, token: String) {
        runCatching {
            val conn = open("$base/storage/v1/object/$bucket/$key", "DELETE", token)
            conn.responseCode
            conn.inputStream?.use { it.readBytes() }
        }
    }

    /** The public bucket serves an approved payload with no auth, so this proves what an install will see. */
    private fun headPublicPayload(publicPath: String): StoreResult<Unit> = try {
        val conn = (URL("$base/storage/v1/object/public/$PAYLOADS/$publicPath").openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // One byte is all the proof needed, and a 5 MB archive is not worth downloading for it.
                setRequestProperty("Range", "bytes=0-0")
            }
        val code = conn.responseCode
        (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
        if (code in 200..299) StoreResult.Ok(Unit) else StoreResult.Failed("HTTP $code", code)
    } catch (e: Exception) {
        StoreResult.Unavailable(e.message ?: "Network unavailable")
    }

    private fun storagePost(path: String, body: String, token: String): StoreResult<String> =
        request("POST", "$base$path", body, token)

    // ---- transport ----

    private fun rpc(name: String, body: String, token: String): StoreResult<String> =
        request("POST", "$base/rest/v1/rpc/$name", body, token)

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun request(method: String, url: String, body: String?, token: String): StoreResult<String> {
        if (!configured) return StoreResult.Unavailable("No store endpoint configured")
        return try {
            val conn = open(url, method, token).apply {
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            when {
                code in 200..299 -> StoreResult.Ok(text)
                // 401 is "not signed in"; 403 is "signed in and not a moderator", which the function body
                // says in as many words. Telling a signed-in moderator to sign in would be nonsense.
                code == 401 -> StoreResult.Failed("Sign in to moderate", code)
                code == 403 -> StoreResult.Failed(errorMessage(text) ?: "You are not a moderator", code)
                code == 429 || code >= 500 -> StoreResult.Unavailable("Store unavailable (HTTP $code)")
                else -> StoreResult.Failed(errorMessage(text) ?: "The store rejected that", code)
            }
        } catch (e: Exception) {
            StoreResult.Unavailable(e.message ?: "Network unavailable")
        }
    }

    private fun errorMessage(body: String): String? {
        val j = JsonReader.parseOrNull(body) ?: return null
        return listOf("message", "error", "msg")
            .firstNotNullOfOrNull { JsonReader.str(j, it)?.takeIf { s -> s.isNotBlank() } }
    }

    private fun unit(result: StoreResult<String>): StoreResult<Unit> = when (result) {
        is StoreResult.Ok -> StoreResult.Ok(Unit)
        is StoreResult.Unavailable -> StoreResult.Unavailable(result.reason)
        is StoreResult.Failed -> StoreResult.Failed(result.message, result.status)
    }

    companion object {
        private const val UPLOADS = "store-uploads"
        private const val PAYLOADS = "store-payloads"
        private const val MEDIA = "store-media"

        private fun q(s: String) = SupabaseStoreSource.jsonStr(s)

        private fun extensionOf(path: String): String =
            path.substringAfterLast('.', "png").lowercase().takeIf { it.length in 1..5 } ?: "png"

        /**
         * One queue entry. A row with no version id or no listing is dropped rather than rendered
         * half-blank: it could not be acted on, and a card that cannot be acted on is worse than a gap.
         */
        internal fun parseSubmission(value: Any?): PendingSubmission? {
            val id = JsonReader.str(value, "id") ?: return null
            val listingJson = JsonReader.obj(value)?.get("item") ?: return null
            val slug = JsonReader.str(listingJson, "slug") ?: return null
            return PendingSubmission(
                versionId = id,
                version = JsonReader.str(value, "version").orEmpty(),
                versionCode = JsonReader.int(value, "versionCode"),
                status = JsonReader.str(value, "status").orEmpty(),
                storagePath = JsonReader.str(value, "storagePath").orEmpty(),
                sizeBytes = JsonReader.long(value, "sizeBytes"),
                sha256 = JsonReader.str(value, "sha256"),
                fileCount = JsonReader.int(value, "fileCount"),
                files = JsonReader.arr(JsonReader.obj(value)?.get("fileManifest")).mapNotNull { f ->
                    JsonReader.str(f, "path")?.let { SubmissionFile(it, JsonReader.long(f, "size")) }
                },
                changelog = JsonReader.str(value, "changelog"),
                createdAt = JsonReader.str(value, "createdAt"),
                reviewedAt = JsonReader.str(value, "reviewedAt"),
                reviewNote = JsonReader.str(value, "reviewNote"),
                screenshotPaths = JsonReader.strings(value, "screenshotPaths"),
                iconPath = JsonReader.str(value, "iconPath"),
                listingPatch = parsePatch(JsonReader.obj(value)?.get("listingPatch")),
                submitter = JsonReader.obj(value)?.get("submitter")?.let { s ->
                    JsonReader.str(s, "id")?.let { uid ->
                        SubmissionSubmitter(
                            userId = uid,
                            handle = JsonReader.str(s, "handle"),
                            displayName = JsonReader.str(s, "displayName"),
                            verified = JsonReader.bool(s, "verified"),
                            banned = JsonReader.bool(s, "banned"),
                        )
                    }
                },
                listing = SubmissionListing(
                    slug = slug,
                    title = JsonReader.str(listingJson, "title").orEmpty().ifBlank { slug },
                    summary = JsonReader.str(listingJson, "summary").orEmpty(),
                    description = JsonReader.str(listingJson, "description").orEmpty(),
                    category = JsonReader.str(listingJson, "category").orEmpty(),
                    language = JsonReader.str(listingJson, "language"),
                    tags = JsonReader.strings(listingJson, "tags"),
                    status = JsonReader.str(listingJson, "status").orEmpty(),
                    iconPath = JsonReader.str(listingJson, "iconPath"),
                    screenshots = JsonReader.strings(listingJson, "screenshots"),
                    installs = JsonReader.int(listingJson, "installs"),
                ),
            )
        }

        /**
         * The proposed listing text, flattened to strings.
         *
         * `tags` arrives as an array and every other key as a string, and the reviewer's card shows both as
         * one line, so joining here keeps the difference out of the UI. A key whose value is neither is
         * dropped rather than stringified into something like `[object]`.
         */
        internal fun parsePatch(value: Any?): Map<String, String> {
            val obj = JsonReader.obj(value) ?: return emptyMap()
            return buildMap {
                for ((k, v) in obj) {
                    when (v) {
                        is String -> put(k, v)
                        is List<*> -> put(k, v.filterIsInstance<String>().joinToString(", "))
                        else -> Unit
                    }
                }
            }
        }

        internal fun parseReport(value: Any?): ReportedContent? {
            val id = JsonReader.str(value, "id") ?: return null
            return ReportedContent(
                reportId = id,
                reason = JsonReader.str(value, "reason").orEmpty(),
                detail = JsonReader.str(value, "detail"),
                createdAt = JsonReader.str(value, "created_at"),
                itemSlug = JsonReader.str(value, "item_slug"),
                itemTitle = JsonReader.str(value, "item_title"),
                isItemReport = JsonReader.bool(value, "is_item_report"),
                reviewAuthorId = JsonReader.str(value, "review_author"),
                reviewStars = JsonReader.int(value, "review_stars"),
                reviewText = JsonReader.str(value, "review_text"),
                reviewStatus = JsonReader.str(value, "review_status"),
            )
        }
    }
}
