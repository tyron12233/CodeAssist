package dev.ide.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiExerciseResult
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiLesson
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.entrancePop
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.close
import dev.ide.ui.generated.resources.learn_copy_to_editor
import dev.ide.ui.generated.resources.learn_finish
import dev.ide.ui.generated.resources.learn_hint_n
import dev.ide.ui.generated.resources.learn_indexing
import dev.ide.ui.generated.resources.learn_need_hint
import dev.ide.ui.generated.resources.learn_next
import dev.ide.ui.generated.resources.learn_check_failed
import dev.ide.ui.generated.resources.learn_output
import dev.ide.ui.generated.resources.learn_preparing
import dev.ide.ui.generated.resources.learn_preparing_sub
import dev.ide.ui.generated.resources.learn_quiz_correct
import dev.ide.ui.generated.resources.learn_quiz_incorrect
import dev.ide.ui.generated.resources.learn_run_check
import dev.ide.ui.generated.resources.learn_running
import dev.ide.ui.generated.resources.learn_show_solution
import dev.ide.ui.generated.resources.learn_solution
import dev.ide.ui.generated.resources.learn_step_progress
import dev.ide.ui.generated.resources.learn_submit
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The step-by-step lesson player. Walks the learner through a lesson's steps: **Concept** (read-only
 * explanation), **Interactive** (an embedded editor whose code is compiled + run + auto-checked by
 * [IdeBackend.learn]), and **Quiz** (a multiple-choice check). Next unlocks per step — always for a concept,
 * once the exercise passes (or the solution is revealed) for an interactive step, and once answered correctly
 * for a quiz. Completing a step records progress; the last step's Finish (or [onExit]) returns to the track.
 */
@Composable
fun LessonPlayerScreen(
    backend: IdeBackend,
    lessonId: String?,
    initialStep: Int,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Show inlay hints in the interactive step's editor (the app-global editor preference). */
    inlayHintsEnabled: Boolean = true,
    /** The platform Compose renderer for [dev.ide.ui.backend.UiContentBlock.ComposePreview] lesson blocks. */
    host: dev.ide.ui.ComposePreviewHost? = null,
) {
    val lesson by produceState<UiLesson?>(null, backend, lessonId) {
        value = runCatching { lessonId?.let { backend.learn.lesson(it) } }.getOrNull()
    }
    val steps = lesson?.steps ?: emptyList()
    var stepIndex by remember(lessonId) { mutableStateOf(initialStep.coerceAtLeast(0)) }
    LaunchedEffect(steps.size) { if (steps.isNotEmpty()) stepIndex = stepIndex.coerceIn(0, steps.size - 1) }
    val step = steps.getOrNull(stepIndex)

    // Advance gating per step: concept always; interactive/quiz flip it when solved/answered.
    var canAdvance by remember(step?.id) { mutableStateOf(step is UiLessonStep.Concept) }

    // Record the learner's place for the Resume banner as they move through the lesson.
    LaunchedEffect(step?.id) { val id = lessonId; if (id != null && step != null) backend.learn.recordVisit(id, stepIndex) }

    Box(modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            // Top bar: close + progress + step counter.
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButtonCa(CaIcons.close, stringResource(Res.string.close), onExit)
                    Text(
                        lesson?.title.orEmpty(),
                        color = Ca.colors.textPrimary, style = Ca.type.headline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (steps.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.learn_step_progress, stepIndex + 1, steps.size),
                            color = Ca.colors.textTertiary, style = Ca.type.caption,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(
                    if (steps.isEmpty()) 0f else (stepIndex + 1).toFloat() / steps.size,
                    track = Ca.colors.surface3, fill = Ca.colors.accent,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Slide steps in the direction of travel (forward on Next, back on Back) with a soft fade.
                AnimatedContent(
                    targetState = stepIndex,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val enter = fadeIn(tween(Motion.BASE, easing = Motion.soft)) +
                            slideInHorizontally(tween(Motion.BASE, easing = Motion.soft)) { w -> (if (forward) w else -w) / 5 }
                        val exit = fadeOut(tween(Motion.FAST)) +
                            slideOutHorizontally(tween(Motion.BASE, easing = Motion.soft)) { w -> (if (forward) -w else w) / 5 }
                        enter togetherWith exit
                    },
                    label = "lesson-step",
                    modifier = Modifier.fillMaxSize(),
                ) { idx ->
                    when (val s = steps.getOrNull(idx)) {
                        is UiLessonStep.Concept -> ConceptStep(s, backend, host)
                        is UiLessonStep.Interactive -> InteractiveStep(s, backend, lessonId, inlayHintsEnabled, host) { canAdvance = true }
                        is UiLessonStep.Quiz -> QuizStep(s) { correct -> canAdvance = correct }
                        null -> {}
                    }
                }
            }

            // Bottom navigation bar: Back + Next / Finish.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (stepIndex > 0) {
                    PlayerButton(stringResource(Res.string.back), primary = false, icon = CaIcons.chevronLeft) { stepIndex-- }
                }
                Spacer(Modifier.weight(1f))
                val isLast = stepIndex >= steps.size - 1
                PlayerButton(
                    if (isLast) stringResource(Res.string.learn_finish) else stringResource(Res.string.learn_next),
                    primary = true,
                    enabled = canAdvance && step != null,
                    icon = if (isLast) CaIcons.check else CaIcons.chevronRight,
                ) {
                    val id = lessonId
                    if (id != null && step != null) backend.learn.markStepComplete(id, step.id)
                    if (isLast) onExit() else stepIndex++
                }
            }
        }
    }
}

// ---- concept step ----

@Composable
private fun ConceptStep(step: UiLessonStep.Concept, backend: IdeBackend, host: dev.ide.ui.ComposePreviewHost?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(step.title, color = Ca.colors.textPrimary, style = Ca.type.title3)
        LessonBlocks(step.blocks, backend = backend, host = host)
        Spacer(Modifier.height(24.dp))
    }
}

// ---- interactive step ----

@Composable
private fun InteractiveStep(
    step: UiLessonStep.Interactive,
    backend: IdeBackend,
    lessonId: String?,
    inlayHintsEnabled: Boolean,
    host: dev.ide.ui.ComposePreviewHost?,
    onSolved: () -> Unit,
) {
    val lang = if (step.language.startsWith("kotlin")) CodeLanguage.Kotlin else CodeLanguage.Java
    val fileName = if (step.language.startsWith("kotlin")) "Main.kt" else "Main.java"
    var session by remember(step.id) { mutableStateOf(EditorSession(step.starterCode, lang)) }
    // Route the embedded editor's language services to the hidden scratch engine for this language, so the
    // lesson buffer gets real completion + diagnostics rather than the (unrelated) open project's.
    val editorBackend = remember(backend, step.language) { LessonEditorBackend(backend, step.language) }
    val scope = rememberCoroutineScope()
    var result by remember(step.id) { mutableStateOf<UiExerciseResult?>(null) }
    var running by remember(step.id) { mutableStateOf(false) }
    var hintsShown by remember(step.id) { mutableStateOf(0) }
    var showSolution by remember(step.id) { mutableStateOf(false) }

    // Build the scratch project's index BEFORE the editor is usable, so completion + diagnostics work from the
    // first keystroke (a lesson never starts against a cold, dumb index).
    var ready by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id) { ready = runCatching { backend.learn.prepare(step.language) }.getOrDefault(true) }
    // Once ready, watch for (re)indexing so we can show a hint and re-analyze when it settles.
    var indexing by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id, ready) {
        if (!ready) return@LaunchedEffect
        while (isActive) {
            indexing = runCatching { backend.learn.indexing(step.language) }.getOrDefault(false)
            delay(700)
        }
    }
    // Live diagnostics + inlay hints: (re)analyze on each settled edit and when indexing finishes; feed the
    // editor's squiggles and the inferred-type/parameter-name hints. Both run against the same buffer snapshot
    // and re-fire when a new edit bumps textRevision (which cancels this pass), so offsets stay consistent.
    LaunchedEffect(step.id, ready, session.textRevision, indexing, inlayHintsEnabled) {
        if (!ready) return@LaunchedEffect
        delay(250)
        val code = session.doc.text
        session.applyAnalysis(runCatching { backend.learn.analyze(step.language, code) }.getOrDefault(emptyList()))
        session.applyInlayHints(
            if (!inlayHintsEnabled) emptyList()
            else runCatching { backend.learn.hints(step.language, code, 0, code.length) }.getOrDefault(emptyList()),
        )
        session.applyCodeFolds(
            runCatching { backend.learn.folds(step.language, code) }.getOrDefault(emptyList())
                .map { FoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault) },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(step.title, color = Ca.colors.textPrimary, style = Ca.type.title3)
        if (step.blocks.isNotEmpty()) LessonBlocks(step.blocks, backend = backend, host = host)

        if (!ready) {
            PreparingCard()
            Spacer(Modifier.height(16.dp))
            return@Column
        }

        AnimatedVisibility(
            visible = indexing,
            enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
        ) { IndexingChip() }

        // The editable exercise buffer (fixed height; scrolls internally).
        val editorShape = RoundedCornerShape(Ca.radius.md)
        Box(
            Modifier.fillMaxWidth().height(260.dp).clip(editorShape)
                .background(Ca.colors.editorBg).border(1.dp, Ca.colors.hairline, editorShape),
        ) {
            CodeEditor(
                path = fileName,
                session = session,
                backend = editorBackend,
                modifier = Modifier.fillMaxSize(),
                completionAutoPopup = true,
            )
        }

        // Action row.
        val checkFailedMsg = stringResource(Res.string.learn_check_failed)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step.hints.isNotEmpty() && hintsShown < step.hints.size) {
                TextAction(stringResource(Res.string.learn_need_hint), CaIcons.lightbulb) { hintsShown++ }
            }
            if (step.solution.isNotBlank() && !showSolution) {
                TextAction(stringResource(Res.string.learn_show_solution), CaIcons.eye) { showSolution = true }
            }
            Spacer(Modifier.weight(1f))
            PlayerButton(
                if (running) stringResource(Res.string.learn_running) else stringResource(Res.string.learn_run_check),
                primary = true,
                enabled = !running,
                icon = CaIcons.play,
            ) {
                if (!running) {
                    running = true
                    val code = session.doc.text
                    scope.launch {
                        val r = runCatching { backend.learn.check(lessonId.orEmpty(), step.id, code) }
                            .getOrElse { UiExerciseResult(passed = false, compiled = false, message = it.message ?: checkFailedMsg) }
                        result = r
                        running = false
                        if (r.passed) onSolved()
                    }
                }
            }
        }

        // key() remounts on each new result so the pop entrance replays every time the learner checks.
        result?.let { r -> key(r) { ResultPanel(r, Modifier.entrancePop()) } }

        // Revealed hints, one card each — each new one slides in as it's revealed.
        for (i in 0 until hintsShown.coerceAtMost(step.hints.size)) {
            HintCard(i + 1, step.hints[i], Modifier.entranceSlideUp())
        }

        // Revealed solution.
        if (showSolution) {
            Column(Modifier.entranceSlideUp(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.learn_solution), color = Ca.colors.textPrimary, style = Ca.type.headline)
                CodeSample(step.solution, step.language)
                TextAction(stringResource(Res.string.learn_copy_to_editor), CaIcons.copy) {
                    session = EditorSession(step.solution, lang)
                    onSolved()
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ResultPanel(result: UiExerciseResult, modifier: Modifier = Modifier) {
    val (icon, tint) = when {
        result.passed -> CaIcons.check to Ca.colors.success
        !result.compiled -> CaIcons.error to Ca.colors.error
        else -> CaIcons.warning to Ca.colors.warning
    }
    val shape = RoundedCornerShape(Ca.radius.md)
    Column(
        modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.10f)).border(1.dp, tint.copy(alpha = 0.30f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Text(result.message, color = Ca.colors.textPrimary, style = Ca.type.subhead)
        }
        if (result.output.isNotBlank()) {
            Text(stringResource(Res.string.learn_output), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).background(Ca.colors.consoleBg)
                    .horizontalScroll(rememberScrollState()).padding(10.dp),
            ) {
                Text(result.output.trimEnd(), color = Ca.colors.textPrimary, style = Ca.type.codeSmall)
            }
        }
        result.diagnostics.forEach { d ->
            Text(d, color = Ca.colors.error, style = Ca.type.codeSmall)
        }
    }
}

@Composable
private fun HintCard(number: Int, hint: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Ca.colors.accentSoft).border(1.dp, Ca.colors.accent.copy(alpha = 0.25f), shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(CaIcons.lightbulb, null, Modifier.size(18.dp), tint = Ca.colors.accent)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.learn_hint_n, number), color = Ca.colors.accent, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
            Text(inlineMarkup(hint), color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
    }
}

// ---- quiz step ----

@Composable
private fun QuizStep(step: UiLessonStep.Quiz, onAnswered: (correct: Boolean) -> Unit) {
    var selected by remember(step.id) { mutableStateOf(-1) }
    var submitted by remember(step.id) { mutableStateOf(false) }
    val correct = selected == step.correctIndex

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(step.title, color = Ca.colors.textPrimary, style = Ca.type.title3)
        Text(step.prompt, color = Ca.colors.textSecondary, style = Ca.type.subhead)
        Spacer(Modifier.height(2.dp))
        step.options.forEachIndexed { i, opt ->
            OptionRow(
                label = opt,
                selected = selected == i,
                revealCorrect = submitted && i == step.correctIndex,
                revealWrong = submitted && selected == i && i != step.correctIndex,
                enabled = !submitted || !correct,
            ) { if (!(submitted && correct)) { selected = i; submitted = false } }
        }
        if (submitted) {
            Text(
                if (correct) stringResource(Res.string.learn_quiz_correct) else stringResource(Res.string.learn_quiz_incorrect),
                color = if (correct) Ca.colors.success else Ca.colors.warning, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold,
            )
            if (step.explanation.isNotBlank()) {
                Text(inlineMarkup(step.explanation), color = Ca.colors.textSecondary, style = Ca.type.footnote)
            }
        }
        if (!(submitted && correct)) {
            PlayerButton(stringResource(Res.string.learn_submit), primary = true, enabled = selected >= 0) {
                submitted = true
                onAnswered(selected == step.correctIndex)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    revealCorrect: Boolean,
    revealWrong: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val border by animateColorAsState(
        when {
            revealCorrect -> Ca.colors.success
            revealWrong -> Ca.colors.error
            selected -> Ca.colors.accent
            else -> Ca.colors.separator
        },
        tween(Motion.FAST), label = "opt-border",
    )
    val fill by animateColorAsState(
        when {
            revealCorrect -> Ca.colors.success.copy(alpha = 0.10f)
            revealWrong -> Ca.colors.error.copy(alpha = 0.10f)
            selected -> Ca.colors.accentSoft
            else -> Ca.colors.surface
        },
        tween(Motion.FAST), label = "opt-fill",
    )
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, border, shape)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val markTint = when {
            revealCorrect -> Ca.colors.success
            revealWrong -> Ca.colors.error
            selected -> Ca.colors.accent
            else -> Ca.colors.textTertiary
        }
        Icon(
            if (revealWrong) CaIcons.close else if (revealCorrect || selected) CaIcons.check else CaIcons.dot,
            null, Modifier.size(if (revealWrong || revealCorrect || selected) 18.dp else 8.dp), tint = markTint,
        )
        Text(label, color = Ca.colors.textPrimary, style = Ca.type.subhead, modifier = Modifier.weight(1f))
    }
}

// ---- shared buttons ----

@Composable
private fun PlayerButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // Animate the fill/label so the primary button lights up smoothly when it becomes enabled (e.g. Next after
    // an exercise passes).
    val bg by animateColorAsState(
        when {
            !primary -> Ca.colors.surface2
            enabled -> Ca.colors.accent
            else -> Ca.colors.surface3
        },
        tween(Motion.BASE), label = "btn-bg",
    )
    val fg by animateColorAsState(
        when {
            !primary -> Ca.colors.textPrimary
            enabled -> Color.White
            else -> Ca.colors.textTertiary
        },
        tween(Motion.BASE), label = "btn-fg",
    )
    val shape = RoundedCornerShape(Ca.radius.control)
    Row(
        modifier
            .height(44.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(16.dp), tint = fg) }
        Text(text, color = fg, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Wraps the host [IdeBackend] so the lesson editor's language services run against the Learn scratch engine
 * for [language] (via [dev.ide.ui.backend.LearnService]) instead of the currently-open project. Critically the
 * editor is a STANDALONE [EditorService] (not a delegate to the real editor): with no project open the real
 * editor's language methods throw "No project is open", so completion + analysis route to the scratch engine
 * and every other method falls back to the interface's safe (empty/null) default.
 */
private class LessonEditorBackend(private val real: IdeBackend, private val language: String) : IdeBackend by real {
    override val editor: EditorService = object : EditorService {
        override fun updateDocument(path: String, text: String) {} // the live buffer is passed per-call
        override fun saveFile(path: String, text: String) {}        // lessons don't persist
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult =
            real.learn.complete(language, text, offset)
        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> =
            real.learn.analyze(language, text)
        override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> =
            real.learn.hints(language, text, startOffset, endOffset)
    }
}

/** Shown while the scratch project's index is building, before the exercise editor is usable. */
@Composable
private fun PreparingCard() {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ca.colors.surface).border(1.dp, Ca.colors.separator, shape)
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Ca.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Text(stringResource(Res.string.learn_preparing), color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(Res.string.learn_preparing_sub),
            color = Ca.colors.textTertiary, style = Ca.type.footnote,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A slim "still indexing" hint above the editor once it's usable but the index is still catching up. */
@Composable
private fun IndexingChip() {
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier.clip(shape).background(Ca.colors.accentSoft).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(color = Ca.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
        Text(stringResource(Res.string.learn_indexing), color = Ca.colors.accent, style = Ca.type.caption2, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TextAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.control)).clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = Ca.colors.accent)
        Text(text, color = Ca.colors.accent, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
    }
}
