package dev.ide.ui.backend

/** Who wrote a question, answer or solution. [handle] opens their public profile when present. */
data class UiLearnAuthor(val name: String, val handle: String? = null, val avatarUrl: String? = null)

/** A question in a lesson's discussion, as listed. [stepTitle] names the exercise it is about, if any. */
data class UiLearnThreadSummary(
    val id: String,
    val lessonId: String,
    val stepId: String?,
    val stepTitle: String?,
    val title: String,
    val author: UiLearnAuthor,
    val answers: Int,
    val accepted: Boolean,
    val hasCode: Boolean,
    val createdAtMs: Long,
    val lastActivityAtMs: Long,
    val mine: Boolean,
)

/** A question with the code and error its asker attached, and the answers to it. */
data class UiLearnThread(
    val id: String,
    val lessonId: String,
    val lessonTitle: String,
    val stepId: String?,
    val stepTitle: String?,
    val title: String,
    val body: String,
    val code: String?,
    val language: String?,
    val errorText: String?,
    val author: UiLearnAuthor,
    val createdAtMs: Long,
    val mine: Boolean,
    val acceptedPost: String?,
    val answers: List<UiLearnAnswer>,
    val canModerate: Boolean,
)

data class UiLearnAnswer(
    val id: String,
    val body: String,
    val code: String?,
    val author: UiLearnAuthor,
    val score: Int,
    val votedByMe: Boolean,
    val mine: Boolean,
    val accepted: Boolean,
    val createdAtMs: Long,
)

data class UiLearnSolution(
    val id: String,
    val code: String,
    val language: String,
    val author: UiLearnAuthor,
    val likes: Int,
    val likedByMe: Boolean,
    val mine: Boolean,
    val createdAtMs: Long,
)

/** Solutions to one exercise; locked (and empty) until the learner has passed it. */
data class UiLearnSolutionPage(val unlocked: Boolean, val solutions: List<UiLearnSolution>)

/** A reported question, answer or solution waiting for a moderator. */
data class UiLearnReport(
    val id: String,
    val kind: String,
    val reason: String,
    val detail: String?,
    val reports: Int,
    val excerpt: String,
    val authorHandle: String?,
    val lessonTitle: String?,
    val hidden: Boolean,
    val createdAtMs: Long,
)

/** A write's outcome: the new item's [id] on success, else the server's [error] sentence. */
data class UiLearnWrite(val id: String? = null, val error: String? = null) {
    val ok: Boolean get() = error == null
}

/** Report reasons, as stored. */
object UiLearnReportReason {
    const val SPAM = "spam"
    const val ABUSE = "abuse"
    const val SPOILER = "spoiler"
    const val WRONG = "wrong"
    const val OTHER = "other"
    val all = listOf(SPAM, ABUSE, SPOILER, WRONG, OTHER)
}
