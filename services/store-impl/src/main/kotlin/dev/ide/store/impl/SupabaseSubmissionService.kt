package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.log.Log
import dev.ide.store.PackagedProject
import dev.ide.store.StoreResult
import dev.ide.store.StoreSubmissionRequest
import dev.ide.store.StoreSubmissionService
import dev.ide.store.StorePublishedItem
import dev.ide.store.StorePublisherProfile
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

        // 1. Publisher row, if this account has never had one. Idempotent, so a second submission does not
        //    fail because it already exists.
        ensurePublisher()

        // 2. Upload. First, because a row without its payload is worse than an orphaned object.
        val shots = uploadScreenshots("${account.userId}/$slug/${request.version}-shots", request.screenshotPaths, token)
        val icon = uploadIcon("${account.userId}/$slug/${request.version}-icon", request.iconPath, token)
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
            icon?.let { field("icon_path", it); comma() }
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
            is StoreResult.Failed -> {
                deleteObject(objectPath, token)
                StoreResult.Failed(versionInsertMessage(r.message, request.version), r.status)
            }
        }
    }

    /**
     * The one database refusal here that a submitter can act on, said plainly.
     *
     * `unique (item_id, version_code)` is what an update hits when the version was already sent — a
     * re-submission after a rejection, or a second try at a version still under review. PostgREST reports
     * that as a constraint name, which tells the user nothing about what to do next. Every other failure is
     * passed through untouched: the quota and validation messages are already written for a person.
     */
    private fun versionInsertMessage(raw: String, version: String): String =
        if ("version_code" in raw && ("duplicate key" in raw || "23505" in raw)) {
            "Version $version has already been submitted for this project. Use a higher version."
        } else {
            raw
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
            // A version the submitter took back is not a submission any more, and leaving it in the list
            // would show it as still in review with a Withdraw button that does nothing.
            "&status=neq.withdrawn" +
            "&select=version,status,review_note,created_at," +
            "store_items!store_item_versions_item_id_fkey(slug,title)" +
            "&order=created_at.desc"
        return when (val r = rest("GET", path, null, token)) {
            is StoreResult.Ok -> StoreResult.Ok(
                JsonReader.arr(JsonReader.parseOrNull(r.value)).mapNotNull { row ->
                    val item = JsonReader.obj(row)?.get("store_items")
                    val slug = item?.let { JsonReader.str(it, "slug") } ?: return@mapNotNull null
                    StoreSubmissionStatus(
                        itemSlug = slug,
                        itemTitle = JsonReader.str(item, "title"),
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
     * The account's own listings, newest first.
     *
     * Two reads rather than one clever query. The items come first (RLS confines this to rows whose
     * `publisher_id` is the caller, so their pending and rejected ones are included, which is exactly what
     * someone looking for "the thing I published" expects to see). The versions follow, because the highest
     * version the account has SENT is not the same as the published one: a pending submission holds a
     * version code that the next one has to clear, and `unique (item_id, version_code)` turns a repeat into
     * a database error rather than an update.
     *
     * The embed names its foreign key for the same reason [mine] does: there are two relationships between
     * these tables and PostgREST answers HTTP 300 without the hint.
     */
    override fun myItems(): StoreResult<List<StorePublishedItem>> {
        if (!configured) return StoreResult.Ok(emptyList())
        val token = accounts.bearer() ?: return StoreResult.Ok(emptyList())
        val uid = accounts.current()?.userId ?: return StoreResult.Ok(emptyList())
        val path = "/rest/v1/store_items" +
            "?publisher_id=eq.$uid" +
            "&select=slug,title,status,icon_path," +
            "store_item_versions!store_items_latest_version_id_fkey(version)" +
            "&order=updated_at.desc"
        val rows = when (val r = rest("GET", path, null, token)) {
            is StoreResult.Ok -> JsonReader.arr(JsonReader.parseOrNull(r.value))
            is StoreResult.Unavailable -> return StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> return StoreResult.Failed(r.message, r.status)
        }
        if (rows.isEmpty()) return StoreResult.Ok(emptyList())
        // Every version this account has sent, by slug, so "the next version" clears the pending ones too.
        val sent: Map<String, List<String>> = when (val r = mine()) {
            is StoreResult.Ok -> r.value.groupBy({ it.itemSlug }, { it.version })
            else -> emptyMap()
        }
        return StoreResult.Ok(
            rows.mapNotNull { row ->
                val slug = JsonReader.str(row, "slug") ?: return@mapNotNull null
                val published = JsonReader.obj(row)?.get("store_item_versions")
                    ?.let { JsonReader.str(it, "version") }
                val highest = (sent[slug].orEmpty() + listOfNotNull(published))
                    .filter { it.isNotBlank() }
                    .maxByOrNull { versionCodeOf(it) }
                StorePublishedItem(
                    slug = slug,
                    title = JsonReader.str(row, "title").orEmpty().ifBlank { slug },
                    status = JsonReader.str(row, "status").orEmpty(),
                    publishedVersion = published,
                    highestVersion = highest,
                    iconPath = JsonReader.str(row, "icon_path"),
                )
            },
        )
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

    // ---- profile ----

    /**
     * The caller's profile, created from the provider identity when the account has none.
     *
     * The hints are what turns a new publisher into `@their-github-login` with their own name and avatar
     * rather than `user-a1b2c3d4`. They are only read when the row is created; the backend will not let a
     * later sign-in overwrite a name its owner has since changed.
     */
    override fun myProfile(): StoreResult<StorePublisherProfile?> {
        if (!configured) return StoreResult.Unavailable("Submissions are not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in to see your profile")
        val id = accounts.providerIdentity()
        val body = buildString {
            append('{')
            append(""""p_handle_hint":""").append(id?.handle?.let { q(it) } ?: "null").append(',')
            append(""""p_display_name":""").append(id?.name?.let { q(it) } ?: "null").append(',')
            append(""""p_avatar_url":""").append(id?.avatarUrl?.let { q(it) } ?: "null")
            append('}')
        }
        return when (val r = rest("POST", "/rest/v1/rpc/store_my_profile", body, token)) {
            is StoreResult.Ok -> StoreResult.Ok(parseProfile(JsonReader.parseOrNull(r.value)))
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    /**
     * Save the editable fields.
     *
     * The backend answers `{ok:false, message}` for a taken handle or a field that is too long, which is
     * returned as [StoreResult.Failed] so the form shows the sentence it was given. A transport failure
     * and a rejected edit are different things and stay different here.
     */
    override fun saveProfile(
        handle: String,
        displayName: String,
        bio: String?,
        location: String?,
        linkUrl: String?,
    ): StoreResult<StorePublisherProfile?> {
        if (!configured) return StoreResult.Unavailable("Submissions are not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in to edit your profile")
        val body = buildString {
            append('{')
            append(""""p_handle":""").append(q(handle)).append(',')
            append(""""p_display_name":""").append(q(displayName)).append(',')
            append(""""p_bio":""").append(bio?.let { q(it) } ?: "null").append(',')
            append(""""p_location":""").append(location?.let { q(it) } ?: "null").append(',')
            append(""""p_link":""").append(linkUrl?.let { q(it) } ?: "null")
            append('}')
        }
        return when (val r = rest("POST", "/rest/v1/rpc/store_save_profile", body, token)) {
            is StoreResult.Ok -> {
                val json = JsonReader.parseOrNull(r.value)
                if (JsonReader.bool(json, "ok")) {
                    StoreResult.Ok(parseProfile(JsonReader.obj(json)?.get("profile")))
                } else {
                    StoreResult.Failed(JsonReader.str(json, "message") ?: "That profile could not be saved")
                }
            }
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    override fun handleAvailable(handle: String): StoreResult<Boolean> {
        if (!configured) return StoreResult.Unavailable("Submissions are not configured in this build")
        val token = accounts.bearer() ?: return StoreResult.Failed("Sign in first")
        val body = """{"p_handle":${q(handle)}}"""
        return when (val r = rest("POST", "/rest/v1/rpc/store_handle_available", body, token)) {
            is StoreResult.Ok -> StoreResult.Ok(r.value.trim().equals("true", ignoreCase = true))
            is StoreResult.Unavailable -> StoreResult.Unavailable(r.reason)
            is StoreResult.Failed -> StoreResult.Failed(r.message, r.status)
        }
    }

    private fun parseProfile(json: Any?): StorePublisherProfile? {
        val handle = JsonReader.str(json, "handle") ?: return null
        return StorePublisherProfile(
            handle = handle,
            displayName = JsonReader.str(json, "displayName") ?: handle,
            bio = JsonReader.str(json, "bio"),
            location = JsonReader.str(json, "location"),
            linkUrl = JsonReader.str(json, "linkUrl"),
            avatarUrl = JsonReader.str(json, "avatarUrl"),
            verified = JsonReader.bool(json, "verified"),
            banned = JsonReader.bool(json, "banned"),
            followers = JsonReader.int(json, "followers"),
            publishedCount = JsonReader.int(json, "publishedCount"),
            pendingCount = JsonReader.int(json, "pendingCount"),
            totalInstalls = JsonReader.int(json, "totalInstalls"),
            totalLikes = JsonReader.int(json, "totalLikes"),
            averageRating = JsonReader.float(json, "averageRating"),
        )
    }

    // ---- steps ----

    /**
     * Make sure the account has a publisher row before its first item points at one.
     *
     * The same get-or-create the profile screen uses, so a first submission and a first visit to the
     * profile produce the same publisher rather than two different naming schemes.
     */
    private fun ensurePublisher() {
        runCatching { myProfile() }
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

    /**
     * Upload the app icon, returning the path it landed at, or null.
     *
     * Best effort like the screenshots, and for the stronger reason: the icon is decoration on a listing,
     * and refusing a submission because the launcher icon would not upload would block a publish over
     * something nobody asked the submitter for.
     */
    private fun uploadIcon(prefix: String, path: String?, token: String): String? {
        val file = File(path ?: return null)
        if (!file.isFile) {
            log.warn("App icon $path is gone; submitting without it")
            return null
        }
        if (file.length() > MAX_SCREENSHOT_BYTES) {
            log.warn("App icon $path is ${file.length()} bytes, over the $MAX_SCREENSHOT_BYTES limit")
            return null
        }
        val extension = file.name.substringAfterLast('.', "png").lowercase()
        val objectPath = "$prefix/icon.$extension"
        return when (val result = uploadBytes(objectPath, file, mimeFor(extension), token)) {
            is StoreResult.Ok -> objectPath
            is StoreResult.Failed -> {
                log.warn("App icon upload rejected (HTTP ${result.status}): ${result.message}")
                null
            }
            is StoreResult.Unavailable -> {
                log.warn("App icon upload failed: ${result.reason}")
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
