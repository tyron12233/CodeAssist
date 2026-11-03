package dev.ide.core.backend

import dev.ide.store.LearnAuthor
import dev.ide.store.LearnCommunityService
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiLearnAnswer
import dev.ide.ui.backend.UiLearnAuthor
import dev.ide.ui.backend.UiLearnReport
import dev.ide.ui.backend.UiLearnSolution
import dev.ide.ui.backend.UiLearnSolutionPage
import dev.ide.ui.backend.UiLearnThread
import dev.ide.ui.backend.UiLearnThreadSummary
import dev.ide.ui.backend.UiLearnWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lesson discussions and shared solutions for [LearnBackend]: calls [community] off the main thread, maps
 * the store's types to the UI's, and names lessons and steps from the catalog ([lessonTitle], [stepTitle])
 * so a thread list can say which exercise a question is about. Every failure becomes either null (reads,
 * which the screen shows as "couldn't load") or the server's own sentence (writes: a rate limit, "sign in").
 */
internal class LearnCommunityOps(
    private val community: LearnCommunityService,
    private val lessonTitle: (lessonId: String) -> String?,
    private val stepTitle: (lessonId: String, stepId: String?) -> String?,
) {
    fun available(): Boolean = community.communityAvailable()

    suspend fun threads(lessonId: String, stepId: String?): List<UiLearnThreadSummary>? = io {
        community.threads(lessonId, stepId).okOrNull()?.map { t ->
            UiLearnThreadSummary(
                id = t.id, lessonId = t.lessonId, stepId = t.stepId, stepTitle = stepTitle(t.lessonId, t.stepId),
                title = t.title, author = t.author.toUi(), answers = t.answers, accepted = t.accepted,
                hasCode = t.hasCode, createdAtMs = millis(t.createdAt), lastActivityAtMs = millis(t.lastActivityAt), mine = t.mine,
            )
        }
    }

    suspend fun threadCount(lessonId: String): Int = io { community.threadCount(lessonId).okOrNull() ?: 0 }

    suspend fun thread(threadId: String): UiLearnThread? = io {
        val d = community.thread(threadId).okOrNull() ?: return@io null
        val t = d.thread
        UiLearnThread(
            id = t.id, lessonId = t.lessonId, lessonTitle = lessonTitle(t.lessonId) ?: t.lessonId,
            stepId = t.stepId, stepTitle = stepTitle(t.lessonId, t.stepId), title = t.title, body = t.body,
            code = t.code, language = t.language, errorText = t.errorText, author = t.author.toUi(),
            createdAtMs = millis(t.createdAt), mine = t.mine, acceptedPost = t.acceptedPost,
            answers = d.posts.map { p ->
                UiLearnAnswer(p.id, p.body, p.code, p.author.toUi(), p.score, p.votedByMe, p.mine, p.accepted, millis(p.createdAt))
            },
            canModerate = d.canModerate,
        )
    }

    suspend fun ask(
        lessonId: String,
        stepId: String?,
        title: String,
        body: String,
        code: String?,
        language: String?,
        errorText: String?,
    ): UiLearnWrite = io {
        community.createThread(lessonId, stepId, title.trim(), body.trim(), code?.takeIf { it.isNotBlank() }, language,
            errorText?.takeIf { it.isNotBlank() }).toWrite()
    }

    suspend fun answer(threadId: String, body: String, code: String?): UiLearnWrite =
        io { community.reply(threadId, body.trim(), code?.takeIf { it.isNotBlank() }).toWrite() }

    suspend fun vote(answerId: String, up: Boolean): String? = io { community.vote(answerId, up).errorOrNull() }
    suspend fun accept(threadId: String, answerId: String?): String? = io { community.accept(threadId, answerId).errorOrNull() }
    suspend fun deleteMine(kind: String, id: String): String? = io { community.deleteMine(kind, id).errorOrNull() }
    suspend fun report(kind: String, id: String, reason: String, detail: String?): String? =
        io { community.report(kind, id, reason, detail?.takeIf { it.isNotBlank() }).errorOrNull() }

    suspend fun solutions(lessonId: String, stepId: String, sort: String): UiLearnSolutionPage? = io {
        community.solutions(lessonId, stepId, sort).okOrNull()?.let { page ->
            UiLearnSolutionPage(
                page.unlocked,
                page.solutions.map { UiLearnSolution(it.id, it.code, it.language, it.author.toUi(), it.likes, it.likedByMe, it.mine, millis(it.createdAt)) },
            )
        }
    }

    suspend fun likeSolution(id: String, like: Boolean): String? = io { community.likeSolution(id, like).errorOrNull() }
    suspend fun setShareSolutions(share: Boolean): String? = io { community.setShareSolutions(share).errorOrNull() }

    /** Share a passing solution. Best effort: a failure here must never touch the learner's pass. */
    suspend fun submitSolution(lessonId: String, stepId: String, code: String, language: String) {
        io { runCatching { community.submitSolution(lessonId, stepId, code, language) } }
    }

    suspend fun reportQueue(): List<UiLearnReport>? = io {
        community.reportQueue().okOrNull()?.map { r ->
            UiLearnReport(
                id = r.id, kind = r.kind, reason = r.reason, detail = r.detail, reports = r.reports, excerpt = r.excerpt,
                authorHandle = r.authorHandle, lessonTitle = r.lessonId?.let { lessonTitle(it) ?: it },
                hidden = r.hidden, createdAtMs = millis(r.createdAt),
            )
        }
    }

    suspend fun resolveReport(id: String, hide: Boolean): String? = io { community.resolveReport(id, hide).errorOrNull() }

    private fun LearnAuthor.toUi() = UiLearnAuthor(name, handle, avatarUrl)

    /** Postgres timestamptz JSON (`2026-11-04T01:02:03.123+00:00`) to epoch millis; 0 when unparseable. */
    private fun millis(iso: String): Long =
        runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

    private fun <T> StoreResult<T>.okOrNull(): T? = (this as? StoreResult.Ok)?.value

    private fun <T> StoreResult<T>.errorOrNull(): String? = when (this) {
        is StoreResult.Ok -> null
        is StoreResult.Unavailable -> reason
        is StoreResult.Failed -> message
    }

    private fun StoreResult<String>.toWrite(): UiLearnWrite = when (this) {
        is StoreResult.Ok -> UiLearnWrite(id = value)
        else -> UiLearnWrite(error = errorOrNull())
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
