package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.log.Log
import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import dev.ide.store.StoreSubmissionService
import dev.ide.store.StoreSubmissionStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Submitting a project for review.
 *
 * The order of operations is the interesting part, and it is chosen so a failure never leaves a row
 * pointing at a payload that is not there:
 *
 *  1. **Upload first**, into `store-uploads/{uid}/{slug}/{version}.zip`. The bucket is private and its
 *     policy only lets a caller write under their own uuid prefix.
 *  2. **Then the item row** (only for a first submission; a new version reuses the existing item).
 *  3. **Then the version row**, `status = 'pending'`, carrying the path, size and sha256.
 *
 * Doing it the other way round — rows first — would produce a pending submission a moderator could open
 * and find empty. If step 3 fails the uploaded object is deleted again, so a retry does not accumulate
 * orphans in the bucket.
 *
 * Nothing here decides what is *in* the zip; [ProjectPackager] does, and its exclusion list is what keeps
 * signing material out of an upload.
 */
class SupabaseSubmissionService(
    url: String,
    private val apiKey: String,
    private val accounts: SupabaseAccountService,
    private val packager: ProjectPackager = ProjectPackager(),
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : StoreSubmissionService {

    private val base = url.trimEnd('/')
    private val configured = url.isNotBlank() && apiKey.isNotBlank()

    override fun submissionsAvailable(): Boolean = configured && accounts.authAvailable()

    override fun pack(projectRoot: String): StoreResult<PackagedProject> {
        if (!configured) return StoreResult.Unavailable("Submissions are not configured in this build")
        return packager.pack(projectRoot)
    }

    override fun submit(
        request: StoreSubmissionRequest,
        packaged: PackagedProject,
    ): StoreResult<StoreSubmissionStatus> {
        if (!configured) return StoreResult.Unavailable("Submissions are not configured in this build")
        val account = accounts.current()
            ?: return StoreResult.Failed("Sign in to submit a project")
        val token = accounts.bearer()
            ?: return StoreResult.Failed("Sign in to submit a project")
        val archive = File(packaged.archivePath)
        if (!archive.isFile) return StoreResult.Failed("The packaged archive is gone; package the project again")

        val slug = request.itemSlug ?: slugFor(request.title, account.userId)
        val objectPath = "${account.userId}/$slug/${request.version}.zip"

        // 1. Publisher row. Created on first submit rather than at signup, so a browse-only account leaves
        //    no public row. Conflict-tolerant: a second submission must not fail because it already exists.
        ensurePublisher(account.userId, token)

        // 2. Upload. First, because a row without its payload is worse than an orphaned object.
        val shots = uploadScreenshots("${account.userId}/$slug/${request.version}-shots", request.screenshotPaths, token)
        when (val up = upload(objectPath, archive, token)) {
            is StoreResult.Ok -> Unit
            is StoreResult.Unavailable -> return StoreResult.Unavailable(up.reason)
            is StoreResult.Failed -> return StoreResult.Failed(up.message, up.status)
        }

        // 3. Item row, for a first submission only.
        var itemId: String? = null
        if (request.itemSlug == null) {
            when (val created = createItem(slug, request, account.userId, token)) {
                is StoreResult.Ok -> itemId = created.value
                is StoreResult.Unavailable -> { deleteObject(objectPath, token); return StoreResult.Unavailable(created.reason) }
                is StoreResult.Failed -> { deleteObject(objectPath, token); return StoreResult.Failed(created.message, created.status) }
            }
        } else {
            when (val found = itemIdFor(slug, token)) {
                is StoreResult.Ok -> itemId = found.value
                is StoreResult.Unavailable -> { deleteObject(objectPath, token); return StoreResult.Unavailable(found.reason) }
                is StoreResult.Failed -> { deleteObject(objectPath, token); return StoreResult.Failed(found.message, found.status) }
            }
        }

        // 4. Version row. If this fails the upload is rolled back, so a retry starts clean.
        val versionCode = versionCodeOf(request.version)
        val body = buildString {
            append('{')
            field("item_id", itemId!!); comma()
            field("version", request.version); comma()
            append(""""version_code":""").append(versionCode).append(',')
            field("storage_path", objectPath); comma()
            append(""""size_bytes":""").append(packaged.totalBytes).append(',')
            field("sha256", packaged.sha256); comma()
            append(""""file_count":""").append(packaged.fileCount).append(',')
            append(""""screenshot_paths":[""")
            shots.forEachIndexed { i, path -> if (i > 0) append(','); append(q(path)) }
            append("],")
            append(""""file_manifest":""").append(manifestJson(packaged)).append(',')
            field("status", "pending"); comma()
            field("submitter_id", account.userId)
            request.changelog?.takeIf { it.isNotBlank() }?.let { comma(); field("changelog", it) }
            append('}')
        }
        return when (val r = rest("POST", "/rest/v1/store_item_versions", body, token, prefer = "return=representation")) {
            is StoreResult.Ok -> StoreResult.Ok(
                StoreSubmissionStatus(itemSlug = slug, version = request.version, status = "pending"),
            )
            is StoreResult.Unavailable -> { deleteObject(objectPath, token); StoreResult.Unavailable(r.reason) }
            is StoreResult.Failed -> { deleteObject(objectPath, token); StoreResult.Failed(r.message, r.status) }
        }
    }

    override fun mine(): StoreResult<List<StoreSubmissionStatus>> {
        if (!configured) return StoreResult.Ok(emptyList())
        val token = accounts.bearer() ?: return StoreResult.Ok(emptyList())
        // RLS confines this to the caller's own rows, so no filter is needed for correctness — the
        // `submitter_id` filter is only there to keep the response small.
        val uid = accounts.current()?.userId ?: return StoreResult.Ok(emptyList())
        // The embed names its foreign key explicitly. There are TWO relationships between these tables —
        // `store_item_versions.item_id -> store_items.id` and the reverse
        // `store_items.latest_version_id -> store_item_versions.id` — and without the hint PostgREST
        // cannot choose, answering HTTP 300 Multiple Choices.
        val path = "/rest/v1/store_item_versions" +
            "?submitter_id=eq.$uid" +
            "&select=version,status,review_note,created_at," +
            "store_items!store_item_versions_item_id_fkey(slug)" +
            "&order=created_at.desc"
        return when (val r = rest("GET", path, null, token)) {
            is StoreResult.Ok -> StoreResult.Ok(
                JsonReader.arr(JsonReader.parseOrNull(r.value)).mapNotNull { row ->
                    val slug = JsonReader.obj(row)?.get("store_items")
                        ?.let { JsonReader.str(it, "slug") } ?: return@mapNotNull null
                    StoreSubmissionStatus(
                        itemSlug = slug,
                        version = JsonReader.str(row, "version").orEmpty(),
                        status = JsonReader.str(row, "status").orEmpty(),
                        reviewNote = JsonReader.str(row, "review_note"),
                        submittedAt = JsonReader.str(row, "created_at"),
                    )
                },
            )
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    /**
     * Withdraw a pending submission.
     *
     * Goes through the `store_withdraw_version` RPC rather than a PostgREST PATCH, because
     * **`HttpURLConnection` rejects the PATCH method on the desktop JDK** (`ProtocolException: Invalid
     * HTTP method: PATCH`) while Android's OkHttp-backed implementation accepts it — a PATCH here would
     * work on a phone and silently report "offline" on desktop. Every store mutation therefore uses POST.
     * Do not add a PATCH call to this class.
     */
    override fun withdraw(itemSlug: String, version: String): StoreResult<Unit> {
        if (!configured) return StoreResult.Unavailable("Submissions are not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        val body = """{"p_slug":${q(itemSlug)},"p_version":${q(version)}}"""
        return when (val r = rest("POST", "/rest/v1/rpc/store_withdraw_version", body, token)) {
            is StoreResult.Ok -> {
                val json = JsonReader.parseOrNull(r.value)
                if (JsonReader.bool(json, "ok")) StoreResult.Ok(Unit)
                else StoreResult.Failed(JsonReader.str(json, "message") ?: "Could not withdraw that submission")
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    // ---- steps ----

    private fun ensurePublisher(userId: String, token: String) {
        val handle = "user-${userId.take(8)}"
        val body = """{"id":${q(userId)},"handle":${q(handle)},"display_name":${q(handle)}}"""
        // `resolution=ignore-duplicates` so a repeat submission is not an error.
        runCatching { rest("POST", "/rest/v1/store_publishers", body, token, prefer = "resolution=ignore-duplicates") }
    }

    private fun createItem(
        slug: String,
        r: StoreSubmissionRequest,
        userId: String,
        token: String,
    ): StoreResult<String> {
        val body = buildString {
            append('{')
            field("slug", slug); comma()
            field("kind", "community"); comma()
            field("title", r.title); comma()
            field("summary", r.summary); comma()
            field("description", r.description); comma()
            field("category", r.category); comma()
            r.language?.let { field("language", it); comma() }
            append(""""tags":""").append(strArray(r.tags)).append(',')
            append(""""highlights":""").append(strArray(r.highlights)).append(',')
            field("publisher_id", userId); comma()
            // 'pending' rather than 'draft': submitting IS the request for review.
            field("status", "pending")
            append('}')
        }
        return when (val res = rest("POST", "/rest/v1/store_items", body, token, prefer = "return=representation")) {
            is StoreResult.Ok -> {
                val id = JsonReader.arr(JsonReader.parseOrNull(res.value)).firstOrNull()
                    ?.let { JsonReader.str(it, "id") }
                if (id != null) StoreResult.Ok(id) else StoreResult.Failed("The store did not return the new item's id")
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(res.reason)
            is StoreResult.Failed -> StoreResult.Failed(res.message, res.status)
        }
    }

    private fun itemIdFor(slug: String, token: String): StoreResult<String> =
        when (val r = rest("GET", "/rest/v1/store_items?slug=eq.$slug&select=id", null, token)) {
            is StoreResult.Ok -> JsonReader.arr(JsonReader.parseOrNull(r.value)).firstOrNull()
                ?.let { JsonReader.str(it, "id") }
                ?.let { StoreResult.Ok(it) }
                ?: StoreResult.Failed("No item called '$slug' that you can publish to")
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }

    /**
     * Upload the submitted screenshots, returning the paths that landed.
     *
     * Best effort per image: a project whose fourth screenshot failed to upload is still a project worth
     * reviewing, and failing the whole submission over one image would lose the archive too. The paths
     * returned are only the ones that actually uploaded, so nothing records an image that is not there.
     */
    /**
     * Upload the screenshots, returning the paths that actually landed.
     *
     * Best-effort per image: one that will not upload must not cost the submitter their submission. But
     * every drop is logged, because silence here once hid a bucket that rejected every image type — the
     * submission succeeded with no screenshots and nothing said why.
     */
    private fun uploadScreenshots(
        prefix: String,
        paths: List<String>,
        token: String,
    ): List<String> = paths.take(MAX_SCREENSHOTS).mapIndexedNotNull { index, local ->
        val file = File(local)
        if (!file.isFile) {
            log.warn("Screenshot $local is gone; submitting without it")
            return@mapIndexedNotNull null
        }
        if (file.length() > MAX_SCREENSHOT_BYTES) {
            log.warn("Screenshot $local is ${file.length()} bytes, over the $MAX_SCREENSHOT_BYTES limit")
            return@mapIndexedNotNull null
        }
        val extension = file.name.substringAfterLast('.', "png").lowercase()
        val objectPath = "$prefix/shot-$index.$extension"
        when (val result = uploadBytes(objectPath, file, mimeFor(extension), token)) {
            is StoreResult.Ok -> objectPath
            is StoreResult.Failed -> {
                log.warn("Screenshot upload rejected (HTTP ${result.status}): ${result.message}")
                null
            }
            is StoreResult.Unavailable -> {
                log.warn("Screenshot upload failed: ${result.reason}")
                null
            }
        }
    }

    private fun mimeFor(extension: String): String = when (extension) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    /** The same streamed upload as the archive, with the content type as a parameter. */
    private fun uploadBytes(
        objectPath: String,
        file: File,
        contentType: String,
        token: String,
    ): StoreResult<Unit> = try {
        val conn = (URL("$base/storage/v1/object/store-uploads/$objectPath").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("x-upsert", "true")
        }
        file.inputStream().buffered().use { input -> conn.outputStream.use { input.copyTo(it) } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code in 200..299) StoreResult.Ok(Unit) else StoreResult.Failed(storageError(text) ?: "Upload rejected", code)
    } catch (e: Exception) {
        StoreResult.Unavailable(e.message ?: "Network unavailable")
    }

    private fun upload(objectPath: String, archive: File, token: String): StoreResult<Unit> = try {
        val conn = (URL("$base/storage/v1/object/store-uploads/$objectPath").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/zip")
            // Streamed, so a 5 MB archive is never copied into the heap.
            setFixedLengthStreamingMode(archive.length())
            // Replace an earlier attempt at the same version rather than 409-ing on a retry.
            setRequestProperty("x-upsert", "true")
        }
        archive.inputStream().buffered().use { input -> conn.outputStream.use { input.copyTo(it) } }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        when {
            code in 200..299 -> StoreResult.Ok(Unit)
            code == 429 || code >= 500 -> StoreResult.Unavailable("Upload service unavailable (HTTP $code)")
            else -> StoreResult.Failed(storageError(text) ?: "Upload rejected", code)
        }
    } catch (e: Exception) {
        StoreResult.Unavailable(e.message ?: "Network unavailable")
    }

    /** Roll back an upload whose rows could not be created, so a retry does not leave orphans behind. */
    private fun deleteObject(objectPath: String, token: String) {
        runCatching {
            val conn = (URL("$base/storage/v1/object/store-uploads/$objectPath").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.responseCode
            conn.inputStream?.use { it.readBytes() }
        }
    }

    private fun storageError(body: String): String? {
        val j = JsonReader.parseOrNull(body) ?: return null
        return listOf("message", "error").firstNotNullOfOrNull { JsonReader.str(j, it)?.takeIf { s -> s.isNotBlank() } }
    }

    private fun rest(
        method: String,
        path: String,
        body: String?,
        token: String,
        prefer: String? = null,
    ): StoreResult<String> = try {
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) setRequestProperty("Prefer", prefer)
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
            code == 429 || code >= 500 -> StoreResult.Unavailable("Store unavailable (HTTP $code)")
            else -> StoreResult.Failed(restError(text) ?: "Store rejected the request", code)
        }
    } catch (e: Exception) {
        StoreResult.Unavailable(e.message ?: "Network unavailable")
    }

    /**
     * PostgREST puts the useful text in `message`, and a quota trigger's `raise exception` lands there
     * too — which is how "already has 3 submissions under review" reaches the user verbatim.
     */
    /**
     * PostgREST puts the useful text in `message`, and a quota trigger's `raise exception` lands there
     * too — which is how "already has 3 submissions under review" reaches the user verbatim.
     *
     * A unique-violation on the slug is the one message that must NOT be passed through: the raw
     * `duplicate key value violates unique constraint "store_items_slug_key"` is not a sentence, and the
     * actual cause is mundane — the account already has a project with that title.
     */
    private fun restError(body: String): String? {
        val j = JsonReader.parseOrNull(body) ?: return null
        val msg = JsonReader.str(j, "message")?.takeIf { it.isNotBlank() } ?: return null
        if (msg.contains("store_items_slug_key") || (msg.contains("duplicate key") && msg.contains("slug"))) {
            return "You already have a project with that name — rename it or publish a new version instead"
        }
        return msg.removePrefix("store: ")
    }

    private fun StringBuilder.field(k: String, v: String) {
        append('"').append(k).append("\":").append(q(v))
    }

    private fun StringBuilder.comma() { append(',') }

    private val log = Log.logger("StoreSubmit")

    companion object {
        /** Matches the CHECK on `store_item_versions.screenshot_paths`. */
        const val MAX_SCREENSHOTS = 6

        /** Matches the `store-media` bucket's per-file limit, so an image cannot fail only on approval. */
        const val MAX_SCREENSHOT_BYTES = 2L * 1024 * 1024

        private fun q(s: String) = SupabaseStoreSource.jsonStr(s)

        private fun strArray(items: List<String>): String =
            items.joinToString(",", "[", "]") { q(it) }

        /** The manifest a reviewer reads instead of downloading and unzipping the archive. */
        internal fun manifestJson(p: PackagedProject): String =
            p.files.joinToString(",", "[", "]") { """{"path":${q(it.path)},"size":${it.sizeBytes}}""" }

        /**
         * A sortable integer from `X.Y.Z`, so the approval trigger's "never let an older version reclaim
         * latest" comparison works. Each component is clamped to three digits, which caps a version at
         * 999.999.999 — far beyond anything a template will see, and cheap to reason about.
         */
        internal fun versionCodeOf(version: String): Int {
            val parts = version.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 999) ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 999) ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 999) ?: 0
            return major * 1_000_000 + minor * 1_000 + patch
        }

        /**
         * A URL-safe slug from the title, suffixed with part of the account id.
         *
         * The suffix is what stops two people who both call their project "Calculator" from colliding on
         * a globally unique column — the first would win and the second would get a confusing failure.
         */
        internal fun slugFor(title: String, userId: String): String {
            val stem = title.lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .split('-').filter { it.isNotBlank() }
                .joinToString("-")
                .take(40)
                .trim('-')
                .ifBlank { "project" }
            return "$stem-${userId.filter { it.isLetterOrDigit() }.take(6)}"
        }
    }
}
