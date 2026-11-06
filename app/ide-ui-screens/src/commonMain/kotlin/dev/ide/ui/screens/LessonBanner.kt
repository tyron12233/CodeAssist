package dev.ide.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.ads.rememberAds
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiContentBlock
import dev.ide.ui.backend.UiExerciseResult
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.components.CodeSample
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.entrancePop
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.learn_check
import dev.ide.ui.generated.resources.learn_check_failed
import dev.ide.ui.generated.resources.learn_check_layout
import dev.ide.ui.generated.resources.learn_checking
import dev.ide.ui.generated.resources.learn_collapse_task
import dev.ide.ui.generated.resources.learn_copy_to_editor
import dev.ide.ui.generated.resources.learn_done
import dev.ide.ui.generated.resources.learn_expand_task
import dev.ide.ui.generated.resources.learn_finish
import dev.ide.ui.generated.resources.learn_more_actions
import dev.ide.ui.generated.resources.learn_need_hint
import dev.ide.ui.generated.resources.learn_next
import dev.ide.ui.generated.resources.learn_running
import dev.ide.ui.generated.resources.learn_show_solution
import dev.ide.ui.generated.resources.learn_solution
import dev.ide.ui.generated.resources.learn_step_progress
import dev.ide.ui.generated.resources.learn_step_short
import dev.ide.ui.generated.resources.learn_task_less
import dev.ide.ui.generated.resources.learn_task_more
import dev.ide.ui.generated.resources.learn_menu_stuck
import dev.ide.ui.generated.resources.learn_menu_community
import dev.ide.ui.generated.resources.learn_hint_progress
import dev.ide.ui.generated.resources.learn_solution_sub
import dev.ide.ui.generated.resources.learn_ask_sub
import dev.ide.ui.generated.resources.learn_discussion_sub
import dev.ide.ui.generated.resources.learn_solutions_sub
import dev.ide.ui.generated.resources.learn_check_again
import dev.ide.ui.generated.resources.learn_previous_step
import dev.ide.ui.generated.resources.learn_collapse_sub
import dev.ide.ui.generated.resources.learn_ask_for_help
import dev.ide.ui.generated.resources.learn_discussion
import dev.ide.ui.generated.resources.learn_discussion_count
import dev.ide.ui.generated.resources.learn_see_solutions
import dev.ide.ui.icons.CaIcons
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The lesson task banner over the full editor: lessons with exercises run in a real project, and this is the
 * lesson around it.
 *
 * It follows AIDE's trainer header, which sits above the code and holds only the task, and keeps out of the
 * way while the learner works:
 *
 * - A **reading** or **quiz** step opens the banner tall, so the text has room; nobody is editing then.
 * - An **exercise** is one header row (close, step, the main button, a menu for Hint / Solution / Back) and
 *   the task clipped to two lines with "More". The main button is Check until the exercise passes, then
 *   Next or Finish.
 * - It **folds itself** to a one-line pill as soon as the learner types in the step's file or the keyboard
 *   opens, and a new Check result unfolds it.
 *
 * The step's file is put in place in the workspace ([dev.ide.ui.backend.LearnService.prepareStep]) and
 * opened as an editor tab. Check reads that tab's live buffer and runs it in the same workspace. Progress,
 * gating and the checkpoint summary come from the same [LessonPlayerState] as the stand-alone player.
 */
@Composable
fun LessonBanner(
    backend: IdeBackend,
    ui: IdeUiState,
    lessonId: String,
    initialStep: Int,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    host: dev.ide.ui.ComposePreviewHost? = null,
    /** The banner moved to another step; the host remembers it for coming back from a discussion. */
    onStepChanged: (Int) -> Unit = {},
    /** The lesson's discussion, scoped to [stepId] when on an exercise. Null hides it. */
    onOpenDiscussion: ((stepId: String?) -> Unit)? = null,
    /** "Ask for help": the question form with the learner's code and what went wrong attached. */
    onAskForHelp: ((stepId: String, code: String?, errorText: String?) -> Unit)? = null,
    /** Other learners' solutions to an exercise the learner has passed. */
    onOpenSolutions: ((stepId: String) -> Unit)? = null,
) {
    val state = rememberLessonPlayerState(backend, lessonId, initialStep)
    LaunchedEffect(state.stepIndex) { onStepChanged(state.stepIndex) }
    val community = onOpenDiscussion != null && backend.learn.communityAvailable()
    val threadCount by androidx.compose.runtime.produceState(0, lessonId, community) {
        value = if (community) runCatching { backend.learn.threadCount(lessonId) }.getOrDefault(0) else 0
    }
    val lesson = state.lesson
    val step = state.step
    val ads = rememberAds()
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme

    var collapsed by remember { mutableStateOf(false) }
    var filePath by remember(step?.id) { mutableStateOf<String?>(null) }
    var result by remember(step?.id) { mutableStateOf<UiExerciseResult?>(null) }
    var running by remember(step?.id) { mutableStateOf(false) }
    var hintsShown by remember(step?.id) { mutableStateOf(0) }
    var showSolution by remember(step?.id) { mutableStateOf(false) }
    var taskExpanded by remember(step?.id) { mutableStateOf(false) }

    // Entering an exercise: put its file in place and open it. Reading and quiz steps always show expanded.
    LaunchedEffect(step?.id) {
        val exercise = step as? UiLessonStep.Interactive
        collapsed = false
        // A lesson about the IDE keeps the bottom bar; a coding lesson hides it.
        ui.learnFocus = exercise?.language?.startsWith("ide-") != true
        if (exercise == null) return@LaunchedEffect
        val path = runCatching { backend.learn.prepareStep(lessonId, exercise.id) }.getOrNull() ?: return@LaunchedEffect
        filePath = path
        ui.openSuspend(path, path.substringAfterLast('/'))
    }

    // Fold away once the learner starts working: an edit to the step's file (the first value is the file as
    // opened, so it is skipped) ...
    LaunchedEffect(filePath) {
        val path = filePath ?: return@LaunchedEffect
        snapshotFlow { ui.openFiles.firstOrNull { it.path == path }?.session?.textRevision }
            .drop(1)
            .collect { rev -> if (rev != null && !running) collapsed = true }
    }
    // ... or the keyboard coming up over the editor.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom > 0) {
        if (imeBottom > 0 && step is UiLessonStep.Interactive) collapsed = true
    }

    val checkFailedMsg = stringResource(Res.string.learn_check_failed)
    fun check(exercise: UiLessonStep.Interactive) {
        if (running) return
        val tour = exercise.language.startsWith("ide-")
        // A hands-on step is graded from the workspace's files, so unsaved edits are written first.
        if (tour) ui.saveAllNow()
        val code = ui.openFiles.firstOrNull { it.path == filePath }?.session?.doc?.text ?: if (tour) "" else return
        running = true
        scope.launch {
            val r = runCatching { backend.learn.check(lessonId, exercise.id, code) }
                .getOrElse { UiExerciseResult(passed = false, compiled = false, message = it.message ?: checkFailedMsg) }
            result = r
            running = false
            if (r.passed) state.allowAdvance(true)
            // A result is worth reading: unfold the banner to show it.
            collapsed = false
        }
    }

    fun copySolution(exercise: UiLessonStep.Interactive) {
        scope.launch {
            val path = runCatching { backend.learn.prepareStep(lessonId, exercise.id, exercise.solution) }.getOrNull()
                ?: return@launch
            filePath = path
            ui.openSuspend(path, path.substringAfterLast('/'))
            state.allowAdvance(true)
        }
    }

    fun advance() = state.next {
        if (ads?.shouldShowLessonInterstitial() == true) ads.showInterstitial("lesson")
        onExit()
    }

    Column(modifier.fillMaxWidth()) {
        // Square-cornered with a hairline under it: the editor's top bar directly below shares the banner's
        // container color, so rounded corners exposed the darker window background as wedges between the two.
        Surface(color = c.surfaceContainer, contentColor = c.onSurface, modifier = Modifier.fillMaxWidth().animateContentSize()) {
            val exercise = step as? UiLessonStep.Interactive
            if (exercise != null && !state.showingSummary) {
                val primaryLabel = when {
                    state.canAdvance && state.isLast -> stringResource(Res.string.learn_finish)
                    state.canAdvance -> stringResource(Res.string.learn_next)
                    running && exercise.language == "xml-layout" -> stringResource(Res.string.learn_checking)
                    running -> stringResource(Res.string.learn_running)
                    exercise.language == "xml-layout" -> stringResource(Res.string.learn_check_layout)
                    else -> stringResource(Res.string.learn_check)
                }
                val primaryIcon = when {
                    state.canAdvance && state.isLast -> CaIcons.check
                    state.canAdvance -> CaIcons.chevronRight
                    else -> CaIcons.play
                }
                val onPrimary = { if (state.canAdvance) advance() else check(exercise) }
                if (collapsed) {
                    CollapsedPill(
                        stepLabel = stringResource(Res.string.learn_step_short, state.stepIndex + 1, state.steps.size),
                        title = exercise.title,
                        primaryLabel = primaryLabel,
                        primaryIcon = primaryIcon,
                        primaryEnabled = !running,
                        onPrimary = onPrimary,
                        onExpand = { collapsed = false },
                    )
                } else {
                    val code = { ui.openFiles.firstOrNull { it.path == filePath }?.session?.doc?.text }
                    ExerciseHeader(
                        exercise = exercise,
                        backend = backend,
                        host = host,
                        stepLabel = stringResource(Res.string.learn_step_short, state.stepIndex + 1, state.steps.size),
                        progress = state.progress,
                        result = result,
                        hintsShown = hintsShown,
                        showSolution = showSolution,
                        taskExpanded = taskExpanded,
                        primaryLabel = primaryLabel,
                        primaryIcon = primaryIcon,
                        primaryEnabled = !running,
                        canGoBack = state.stepIndex > 0,
                        canRecheck = state.canAdvance && !running,
                        onPrimary = onPrimary,
                        onRecheck = { check(exercise) },
                        onExit = onExit,
                        onCollapse = { collapsed = true },
                        onToggleTask = { taskExpanded = !taskExpanded },
                        onHint = { hintsShown++; taskExpanded = true },
                        onShowSolution = { showSolution = true; taskExpanded = true },
                        onCopySolution = { copySolution(exercise) },
                        onBack = state::back,
                        threadCount = threadCount,
                        onOpenDiscussion = if (community) ({ onOpenDiscussion?.invoke(exercise.id) }) else null,
                        onAskForHelp = if (community && onAskForHelp != null) ({
                            onAskForHelp(exercise.id, code(), result?.takeIf { !it.passed }?.let(::describeFailure))
                        }) else null,
                        // A hands-on CodeAssist step has nothing to share: its "solution" is what the learner did in the IDE.
                        onOpenSolutions = if (community && onOpenSolutions != null && state.canAdvance && !exercise.language.startsWith("ide-")) ({ onOpenSolutions(exercise.id) }) else null,
                    )
                }
                return@Surface
            }

            // Reading, quiz, and the checkpoint summary: the tall sheet with its own Back / Next row.
            Column(Modifier.padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButtonCa(CaIcons.close, stringResource(Res.string.close), onExit)
                    Text(
                        lesson?.title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.steps.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.learn_step_progress, state.stepIndex + 1, state.steps.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.outline,
                        )
                    }
                    if (community) DiscussionButton(threadCount) { onOpenDiscussion?.invoke(null) }
                }
                ProgressBar(
                    state.progress,
                    track = c.surfaceContainerHighest,
                    fill = c.primary,
                    modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 8.dp),
                )
                Box(Modifier.padding(start = 6.dp)) {
                    when {
                        state.showingSummary -> {
                            val (right, total) = state.firstTryScore
                            Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) { CheckpointSummary(right, total, Modifier.entrancePop()) }
                        }
                        step is UiLessonStep.Concept -> Box(Modifier.heightIn(max = 380.dp)) { ConceptStep(step, backend, host) }
                        step is UiLessonStep.Quiz -> Box(Modifier.heightIn(max = 400.dp)) {
                            QuizStep(step) { correct ->
                                state.recordQuizAnswer(step.id, correct)
                                state.allowAdvance(correct)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.stepIndex > 0 && !state.showingSummary) {
                        PlayerButton(stringResource(Res.string.back), primary = false, icon = CaIcons.chevronLeft, onClick = state::back)
                    }
                    Spacer(Modifier.weight(1f))
                    PlayerButton(
                        when {
                            state.showingSummary -> stringResource(Res.string.learn_done)
                            state.isLast -> stringResource(Res.string.learn_finish)
                            else -> stringResource(Res.string.learn_next)
                        },
                        primary = true,
                        enabled = state.canAdvance && step != null,
                        icon = if (state.isLast) CaIcons.check else CaIcons.chevronRight,
                    ) { advance() }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
    }
}

/**
 * An exercise's compact header: one row of controls, a thin progress bar, and the task clipped to two lines.
 * "More" (or asking for a hint or the solution) opens the rest, which scrolls inside a capped height.
 */
@Composable
private fun ExerciseHeader(
    exercise: UiLessonStep.Interactive,
    backend: IdeBackend,
    host: dev.ide.ui.ComposePreviewHost?,
    stepLabel: String,
    progress: Float,
    result: UiExerciseResult?,
    hintsShown: Int,
    showSolution: Boolean,
    taskExpanded: Boolean,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryEnabled: Boolean,
    canGoBack: Boolean,
    canRecheck: Boolean,
    onPrimary: () -> Unit,
    onRecheck: () -> Unit,
    onExit: () -> Unit,
    onCollapse: () -> Unit,
    onToggleTask: () -> Unit,
    onHint: () -> Unit,
    onShowSolution: () -> Unit,
    onCopySolution: () -> Unit,
    onBack: () -> Unit,
    threadCount: Int = 0,
    onOpenDiscussion: (() -> Unit)? = null,
    onAskForHelp: (() -> Unit)? = null,
    onOpenSolutions: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val isLayout = exercise.language == "xml-layout"
    var menuOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // A fresh result sits right under the row; bring it into view even if the learner had scrolled away.
    LaunchedEffect(result) { if (result != null) scroll.animateScrollTo(0) }

    Column(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButtonCa(CaIcons.close, stringResource(Res.string.close), onExit)
            Text(
                exercise.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(stepLabel, style = MaterialTheme.typography.labelMedium, color = c.outline)
            Spacer(Modifier.size(4.dp))
            PlayerButton(primaryLabel, primary = true, enabled = primaryEnabled, icon = primaryIcon, compact = true, onClick = onPrimary)
            Box {
                MoreButton(stringResource(Res.string.learn_more_actions)) { menuOpen = true }
                LessonMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    hint = (hintsShown + 1).takeIf { exercise.hints.isNotEmpty() && hintsShown < exercise.hints.size }
                        ?.let { it to exercise.hints.size },
                    onHint = onHint,
                    onShowSolution = onShowSolution.takeIf { exercise.solution.isNotBlank() && !showSolution },
                    onAskForHelp = onAskForHelp,
                    threadCount = threadCount,
                    onOpenDiscussion = onOpenDiscussion,
                    onOpenSolutions = onOpenSolutions,
                    onRecheck = onRecheck.takeIf { canRecheck },
                    recheckLabel = stringResource(if (isLayout) Res.string.learn_check_layout else Res.string.learn_check_again),
                    onBack = onBack.takeIf { canGoBack },
                    onCollapse = onCollapse,
                )
            }
        }
        ProgressBar(progress, track = c.surfaceContainerHighest, fill = c.primary, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp))

        Column(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(scroll).padding(start = 12.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            result?.let { r -> key(r) { ResultPanel(r, Modifier.entrancePop()) } }
            // The next move after a result: help when it failed, other approaches when it passed.
            if (result != null && !result.passed && onAskForHelp != null) {
                TextAction(stringResource(Res.string.learn_ask_for_help), CaIcons.lightbulb, onAskForHelp)
            }
            if (result?.passed == true && onOpenSolutions != null) {
                TextAction(stringResource(Res.string.learn_see_solutions), CaIcons.eye, onOpenSolutions)
            }
            if (taskExpanded) {
                if (exercise.blocks.isNotEmpty()) LessonBlocks(exercise.blocks, backend = backend, host = host)
            } else {
                Text(
                    inlineMarkup(taskSummary(exercise)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            for (i in 0 until hintsShown.coerceAtMost(exercise.hints.size)) {
                HintCard(i + 1, exercise.hints[i], Modifier.entranceSlideUp())
            }
            if (showSolution) {
                Column(Modifier.entranceSlideUp(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.learn_solution), style = MaterialTheme.typography.titleSmall)
                    CodeSample(exercise.solution, if (isLayout) "xml" else exercise.language)
                    TextAction(stringResource(Res.string.learn_copy_to_editor), CaIcons.copy, onCopySolution)
                }
            }
        }
        if (exercise.blocks.size > 1 || taskSummary(exercise).length > 90) {
            Box(Modifier.padding(start = 4.dp)) {
                TextAction(
                    stringResource(if (taskExpanded) Res.string.learn_task_less else Res.string.learn_task_more),
                    if (taskExpanded) CaIcons.chevronUp else CaIcons.chevronDown,
                    onToggleTask,
                )
            }
        }
    }
}

/** The task's first paragraph, the part worth showing when the header is folded to two lines. */
private fun taskSummary(exercise: UiLessonStep.Interactive): String =
    exercise.blocks.firstNotNullOfOrNull { (it as? UiContentBlock.Text)?.md?.trim() } ?: exercise.title

/** The folded banner: which step, what it asks, the main button, and the way back to the full task. */
@Composable
private fun CollapsedPill(
    stepLabel: String,
    title: String,
    primaryLabel: String,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onExpand: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(width = 40.dp, height = 26.dp).background(c.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            Text(stepLabel, style = MaterialTheme.typography.labelMedium, color = c.primary, fontWeight = FontWeight.Bold)
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        PlayerButton(primaryLabel, primary = true, enabled = primaryEnabled, icon = primaryIcon, compact = true, onClick = onPrimary)
        IconButtonCa(CaIcons.chevronDown, stringResource(Res.string.learn_expand_task), onExpand)
    }
}

/** The overflow button. CaIcons has no vertical-dots vector, so it is the symbol font's `more_vert`. */
@Composable
private fun MoreButton(description: String, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        dev.ide.ui.theme.Symbol(dev.ide.ui.icons.CaSymbols.moreVert, contentDescription = description, size = 20.dp)
    }
}

/** A check failure as a helper reads it: the verdict, then the compiler's errors or the program's output. */
private fun describeFailure(r: UiExerciseResult): String = buildString {
    append(r.message.trim())
    if (r.diagnostics.isNotEmpty()) append("\n\n").append(r.diagnostics.joinToString("\n"))
    else if (r.output.isNotBlank()) append("\n\nOutput:\n").append(r.output.trimEnd())
}.take(4000)

/** The reading/quiz header's way into the lesson's discussion, with its question count. */
@Composable
private fun DiscussionButton(count: Int, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        dev.ide.ui.theme.Symbol(dev.ide.ui.icons.CaSymbols.forum, contentDescription = null, size = 18.dp)
        Spacer(Modifier.size(6.dp))
        Text(if (count > 0) count.toString() else stringResource(Res.string.learn_discussion))
    }
}

/**
 * The exercise menu, on the app's own menu surface ([dev.ide.ui.components.CaDropdownMenu]) and grouped by
 * what the learner is after: getting unstuck, then learning from others, then moving around the lesson.
 * Each row says what it does on a second line, and the discussion carries its question count as a badge.
 */
@Composable
private fun LessonMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    hint: Pair<Int, Int>?,
    onHint: () -> Unit,
    onShowSolution: (() -> Unit)?,
    onAskForHelp: (() -> Unit)?,
    threadCount: Int,
    onOpenDiscussion: (() -> Unit)?,
    onOpenSolutions: (() -> Unit)?,
    onRecheck: (() -> Unit)?,
    recheckLabel: String,
    onBack: (() -> Unit)?,
    onCollapse: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    fun pick(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    dev.ide.ui.components.CaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        iconLed = true,
        modifier = Modifier.widthIn(min = 248.dp),
    ) {
        val stuck = hint != null || onShowSolution != null || onAskForHelp != null
        if (stuck) MenuSection(stringResource(Res.string.learn_menu_stuck))
        hint?.let { (n, total) ->
            dev.ide.ui.components.CaMenuItem(
                label = stringResource(Res.string.learn_need_hint),
                supporting = stringResource(Res.string.learn_hint_progress, n, total),
                icon = CaIcons.lightbulb,
                onClick = pick(onHint),
            )
        }
        onShowSolution?.let {
            dev.ide.ui.components.CaMenuItem(
                label = stringResource(Res.string.learn_show_solution),
                supporting = stringResource(Res.string.learn_solution_sub),
                icon = CaIcons.eye,
                onClick = pick(it),
            )
        }
        onAskForHelp?.let {
            dev.ide.ui.components.CaMenuItem(
                label = stringResource(Res.string.learn_ask_for_help),
                supporting = stringResource(Res.string.learn_ask_sub),
                leading = { MenuSymbol(dev.ide.ui.icons.CaSymbols.forum) },
                onClick = pick(it),
            )
        }
        if (onOpenDiscussion != null || onOpenSolutions != null) {
            if (stuck) MenuDivider()
            MenuSection(stringResource(Res.string.learn_menu_community))
        }
        onOpenDiscussion?.let {
            dev.ide.ui.components.CaMenuItem(
                label = stringResource(Res.string.learn_discussion),
                supporting = stringResource(Res.string.learn_discussion_sub),
                leading = { MenuSymbol(dev.ide.ui.icons.CaSymbols.reviews) },
                trailing = if (threadCount > 0) ({ CountBadge(threadCount) }) else null,
                onClick = pick(it),
            )
        }
        onOpenSolutions?.let {
            dev.ide.ui.components.CaMenuItem(
                label = stringResource(Res.string.learn_see_solutions),
                supporting = stringResource(Res.string.learn_solutions_sub),
                leading = { MenuSymbol(dev.ide.ui.icons.CaSymbols.star) },
                onClick = pick(it),
            )
        }
        MenuDivider()
        onRecheck?.let { dev.ide.ui.components.CaMenuItem(label = recheckLabel, icon = CaIcons.play, onClick = pick(it)) }
        onBack?.let {
            dev.ide.ui.components.CaMenuItem(label = stringResource(Res.string.learn_previous_step), icon = CaIcons.chevronLeft, onClick = pick(it))
        }
        dev.ide.ui.components.CaMenuItem(
            label = stringResource(Res.string.learn_collapse_task),
            supporting = stringResource(Res.string.learn_collapse_sub),
            icon = CaIcons.chevronUp,
            onClick = pick(onCollapse),
        )
    }
}

@Composable
private fun MenuSection(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun MenuSymbol(glyph: Char) {
    dev.ide.ui.theme.Symbol(glyph, contentDescription = null, size = dev.ide.ui.components.CaMenuDefaults.IconSize)
}

@Composable
private fun CountBadge(count: Int) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
