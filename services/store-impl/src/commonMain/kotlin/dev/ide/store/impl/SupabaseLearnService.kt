package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.platform.JsonWriter
import dev.ide.store.LearnAccountState
import dev.ide.store.LearnBadge
import dev.ide.store.LearnAuthor
import dev.ide.store.LearnCatalogPayload
import dev.ide.store.LearnCommunityService
import dev.ide.store.LearnPost
import dev.ide.store.LearnReportItem
import dev.ide.store.LearnSolution
import dev.ide.store.LearnSolutionPage
import dev.ide.store.LearnThread
import dev.ide.store.LearnThreadDetail
import dev.ide.store.LearnThreadSummary
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
) : LearnRemoteService, LearnCommunityService {

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

    // ---- community ----

    override fun communityAvailable(): Boolean = configured

    override fun threads(lessonId: String, stepId: String?, limit: Int, offset: Int): StoreResult<List<LearnThreadSummary>> =
        rpc("learn_threads_list", JsonWriter.obj("p_lesson" to lessonId, "p_step" to stepId, "p_limit" to limit, "p_offset" to offset))
            .mapOk { root -> JsonReader.arr(root).mapNotNull { parseThreadSummary(it) } }

    override fun threadCount(lessonId: String): StoreResult<Int> =
        rpc("learn_thread_count", JsonWriter.obj("p_lesson" to lessonId)).mapOk { (it as? Number)?.toInt() ?: 0 }

    override fun thread(threadId: String): StoreResult<LearnThreadDetail?> =
        rpc("learn_thread_get", JsonWriter.obj("p_thread" to threadId)).mapOk { root ->
            val o = JsonReader.obj(root) ?: return@mapOk null
            val t = JsonReader.obj(o["thread"]) ?: return@mapOk null
            LearnThreadDetail(
                thread = LearnThread(
                    id = JsonReader.str(t, "id").orEmpty(),
                    lessonId = JsonReader.str(t, "lessonId").orEmpty(),
                    stepId = JsonReader.str(t, "stepId"),
                    title = JsonReader.str(t, "title").orEmpty(),
                    body = JsonReader.str(t, "body").orEmpty(),
                    code = JsonReader.str(t, "code"),
                    language = JsonReader.str(t, "language"),
                    errorText = JsonReader.str(t, "errorText"),
                    author = parseAuthor(t["author"]),
                    createdAt = JsonReader.str(t, "createdAt").orEmpty(),
                    mine = JsonReader.bool(t, "mine"),
                    acceptedPost = JsonReader.str(t, "acceptedPost"),
                ),
                posts = JsonReader.arr(o["posts"]).map { p ->
                    LearnPost(
                        id = JsonReader.str(p, "id").orEmpty(),
                        body = JsonReader.str(p, "body").orEmpty(),
                        code = JsonReader.str(p, "code"),
                        author = parseAuthor(JsonReader.obj(p)?.get("author")),
                        score = JsonReader.int(p, "score"),
                        votedByMe = JsonReader.bool(p, "votedByMe"),
                        mine = JsonReader.bool(p, "mine"),
                        accepted = JsonReader.bool(p, "accepted"),
                        createdAt = JsonReader.str(p, "createdAt").orEmpty(),
                    )
                },
                canModerate = JsonReader.bool(o, "canModerate"),
            )
        }

    override fun createThread(
        lessonId: String,
        stepId: String?,
        title: String,
        body: String,
        code: String?,
        language: String?,
        errorText: String?,
    ): StoreResult<String> = write(
        "learn_thread_create",
        JsonWriter.obj(
            "p_lesson" to lessonId, "p_step" to stepId, "p_title" to title, "p_body" to body,
            "p_code" to code, "p_language" to language, "p_error" to errorText,
        ),
    ).mapOk { JsonReader.str(it, "id").orEmpty() }

    override fun reply(threadId: String, body: String, code: String?): StoreResult<String> =
        write("learn_post_create", JsonWriter.obj("p_thread" to threadId, "p_body" to body, "p_code" to code))
            .mapOk { JsonReader.str(it, "id").orEmpty() }

    override fun vote(postId: String, up: Boolean): StoreResult<Int> =
        write("learn_post_vote", JsonWriter.obj("p_post" to postId, "p_up" to up)).mapOk { JsonReader.int(it, "score") }

    override fun accept(threadId: String, postId: String?): StoreResult<Unit> =
        write("learn_thread_accept", JsonWriter.obj("p_thread" to threadId, "p_post" to postId)).mapOk { }

    override fun deleteMine(kind: String, id: String): StoreResult<Unit> =
        write("learn_delete_mine", JsonWriter.obj("p_kind" to kind, "p_id" to id)).mapOk { }

    override fun report(kind: String, id: String, reason: String, detail: String?): StoreResult<Unit> =
        write("learn_report", JsonWriter.obj("p_kind" to kind, "p_id" to id, "p_reason" to reason, "p_detail" to detail)).mapOk { }

    override fun submitSolution(lessonId: String, stepId: String, code: String, language: String): StoreResult<Boolean> =
        write(
            "learn_solution_submit",
            JsonWriter.obj("p_lesson" to lessonId, "p_step" to stepId, "p_code" to code, "p_language" to language),
        ).mapOk { JsonReader.bool(it, "shared") }

    override fun solutions(lessonId: String, stepId: String, sort: String, limit: Int, offset: Int): StoreResult<LearnSolutionPage> =
        rpc(
            "learn_solutions_list",
            JsonWriter.obj("p_lesson" to lessonId, "p_step" to stepId, "p_sort" to sort, "p_limit" to limit, "p_offset" to offset),
        ).mapOk { root ->
            LearnSolutionPage(
                unlocked = JsonReader.bool(root, "unlocked"),
                solutions = JsonReader.arr(JsonReader.obj(root)?.get("solutions")).map { s ->
                    LearnSolution(
                        id = JsonReader.str(s, "id").orEmpty(),
                        code = JsonReader.str(s, "code").orEmpty(),
                        language = JsonReader.str(s, "language").orEmpty(),
                        author = parseAuthor(JsonReader.obj(s)?.get("author")),
                        likes = JsonReader.int(s, "likes"),
                        likedByMe = JsonReader.bool(s, "likedByMe"),
                        mine = JsonReader.bool(s, "mine"),
                        createdAt = JsonReader.str(s, "createdAt").orEmpty(),
                    )
                },
            )
        }

    override fun likeSolution(solutionId: String, like: Boolean): StoreResult<Int> =
        write("learn_solution_like", JsonWriter.obj("p_solution" to solutionId, "p_like" to like)).mapOk { JsonReader.int(it, "likes") }

    override fun setShareSolutions(share: Boolean): StoreResult<Unit> =
        write("learn_set_share_solutions", JsonWriter.obj("p_share" to share)).mapOk { }

    override fun reportQueue(limit: Int): StoreResult<List<LearnReportItem>> =
        write("learn_report_queue", JsonWriter.obj("p_limit" to limit)).mapOk { root ->
            JsonReader.arr(root).map { r ->
                LearnReportItem(
                    id = JsonReader.str(r, "id").orEmpty(),
                    kind = JsonReader.str(r, "kind").orEmpty(),
                    targetId = JsonReader.str(r, "targetId").orEmpty(),
                    reason = JsonReader.str(r, "reason").orEmpty(),
                    detail = JsonReader.str(r, "detail"),
                    reports = JsonReader.int(r, "reports"),
                    excerpt = JsonReader.str(r, "excerpt").orEmpty(),
                    authorHandle = JsonReader.str(r, "authorHandle"),
                    lessonId = JsonReader.str(r, "lessonId"),
                    stepId = JsonReader.str(r, "stepId"),
                    hidden = JsonReader.bool(r, "hidden"),
                    createdAt = JsonReader.str(r, "createdAt").orEmpty(),
                )
            }
        }

    override fun resolveReport(reportId: String, hide: Boolean): StoreResult<Unit> =
        write("learn_resolve_report", JsonWriter.obj("p_report" to reportId, "p_hide" to hide)).mapOk { }

    /** A write needs an account; say so plainly instead of sending the publishable key and getting a 401. */
    private fun write(name: String, body: String): StoreResult<Any?> {
        if (accounts?.bearer() == null) return StoreResult.Failed("Sign in first", 401)
        return rpc(name, body)
    }

    private fun parseThreadSummary(v: Any?): LearnThreadSummary? {
        val id = JsonReader.str(v, "id") ?: return null
        return LearnThreadSummary(
            id = id,
            lessonId = JsonReader.str(v, "lessonId").orEmpty(),
            stepId = JsonReader.str(v, "stepId"),
            title = JsonReader.str(v, "title").orEmpty(),
            author = parseAuthor(JsonReader.obj(v)?.get("author")),
            answers = JsonReader.int(v, "answers"),
            accepted = JsonReader.bool(v, "accepted"),
            hasCode = JsonReader.bool(v, "hasCode"),
            createdAt = JsonReader.str(v, "createdAt").orEmpty(),
            lastActivityAt = JsonReader.str(v, "lastActivityAt").orEmpty(),
            mine = JsonReader.bool(v, "mine"),
        )
    }

    private fun parseAuthor(v: Any?) = LearnAuthor(
        handle = JsonReader.str(v, "handle"),
        name = JsonReader.str(v, "name") ?: "Learner",
        avatarUrl = JsonReader.str(v, "avatarUrl"),
    )

    private inline fun <T> StoreResult<Any?>.mapOk(f: (Any?) -> T): StoreResult<T> = when (this) {
        is StoreResult.Ok -> StoreResult.Ok(f(value))
        is StoreResult.Unavailable -> StoreResult.Unavailable(reason)
        // The server's own sentence (a rate limit, "solve it first") is worth showing; see [send].
        is StoreResult.Failed -> StoreResult.Failed(message, status)
    }

    private fun parseAccount(root: Any?): LearnAccountState {
        val o = JsonReader.obj(root) ?: return LearnAccountState()
        val steps = (o["steps"] as? List<*>).orEmpty().mapNotNull { pair ->
            val p = pair as? List<*> ?: return@mapNotNull null
            val l = p.getOrNull(0) as? String ?: return@mapNotNull null
            val s = p.getOrNull(1) as? String ?: return@mapNotNull null
            l to s
        }.toSet()
        return LearnAccountState(
            steps, parseBadges(o["badges"]), parseStreak(o["streak"]),
            shareSolutions = JsonReader.bool(o, "shareSolutions", default = true),
        )
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
        if (r.status !in 200..299) {
            // PostgREST puts a raised exception's text in `message`: a rate limit or "sign in first" is a
            // sentence the learner should see, not "learn_post_create failed".
            val message = JsonReader.str(JsonReader.parseOrNull(r.body), "message")?.takeIf { it.isNotBlank() }
            return StoreResult.Failed(message ?: "$name failed", r.status)
        }
        return StoreResult.Ok(JsonReader.parseOrNull(r.body))
    }
}
