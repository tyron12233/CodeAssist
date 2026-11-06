package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiLesson
import dev.ide.ui.backend.UiLessonKind
import dev.ide.ui.backend.UiLessonStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * State and intents for the lesson player: the loaded lesson, where the learner is in it, and whether the
 * current step lets them move on. Concept steps advance freely; interactive and quiz steps unlock once
 * solved or answered.
 */
@Stable
internal class LessonPlayerState(
    private val backend: IdeBackend,
    private val lessonId: String?,
    initialStep: Int,
    private val scope: CoroutineScope,
) {
    var lesson: UiLesson? by mutableStateOf(null)
        private set
    var stepIndex: Int by mutableStateOf(initialStep.coerceAtLeast(0))
        private set
    var canAdvance: Boolean by mutableStateOf(false)
        private set

    /** Each quiz's FIRST answer (right or wrong), for a checkpoint's score. Retries never change it. */
    private val firstAnswers = mutableStateMapOf<String, Boolean>()

    /** Quizzes answered right on the first try, out of the quizzes in this lesson. */
    val firstTryScore: Pair<Int, Int>
        get() = firstAnswers.count { it.value } to steps.count { it is UiLessonStep.Quiz }

    /** True once a checkpoint's last step is finished: the player shows the score before leaving. */
    var showingSummary: Boolean by mutableStateOf(false)
        private set

    val steps: List<UiLessonStep> get() = lesson?.steps ?: emptyList()
    val step: UiLessonStep? get() = steps.getOrNull(stepIndex)
    val isLast: Boolean get() = stepIndex >= steps.size - 1
    val progress: Float get() = if (steps.isEmpty()) 0f else (stepIndex + 1).toFloat() / steps.size

    init {
        scope.launch {
            lesson = runCatching { lessonId?.let { backend.learn.lesson(it) } }.getOrNull()
            if (steps.isNotEmpty()) stepIndex = stepIndex.coerceIn(0, steps.size - 1)
        }
        // On every step change: reset the advance gate for its kind, and record the learner's place for the
        // Resume banner.
        scope.launch {
            snapshotFlow { step?.id }.collect { id ->
                canAdvance = step is UiLessonStep.Concept
                if (lessonId != null && id != null) backend.learn.recordVisit(lessonId, stepIndex)
            }
        }
    }

    /** An interactive step was solved, or a quiz answered: unlock (or re-lock) the Next button. */
    fun allowAdvance(value: Boolean) { canAdvance = value }

    /** A quiz was submitted. Only the first submission per quiz counts toward the checkpoint score. */
    fun recordQuizAnswer(stepId: String, correct: Boolean) {
        if (stepId !in firstAnswers) firstAnswers[stepId] = correct
    }

    fun back() {
        if (stepIndex > 0) stepIndex--
    }

    /** Mark the step complete, then move on. On the last step [onFinish] leaves the player. */
    fun next(onFinish: () -> Unit) {
        val current = step
        if (lessonId != null && current != null) backend.learn.markStepComplete(lessonId, current.id)
        when {
            !isLast -> stepIndex++
            lesson?.kind == UiLessonKind.CHECKPOINT && !showingSummary -> showingSummary = true
            else -> onFinish()
        }
    }
}

@Composable
internal fun rememberLessonPlayerState(
    backend: IdeBackend,
    lessonId: String?,
    initialStep: Int,
    scope: CoroutineScope = rememberCoroutineScope(),
): LessonPlayerState = remember(backend, lessonId, scope) {
    LessonPlayerState(backend, lessonId, initialStep, scope)
}
