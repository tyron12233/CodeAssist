package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
import dev.ide.ui.ads.rememberAds
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiExerciseResult
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiLessonStep
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.CodeSample
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
import dev.ide.ui.generated.resources.learn_check_layout
import dev.ide.ui.generated.resources.learn_checking
import dev.ide.ui.generated.resources.learn_checkpoint_good
import dev.ide.ui.generated.resources.learn_checkpoint_perfect
import dev.ide.ui.generated.resources.learn_checkpoint_retry
import dev.ide.ui.generated.resources.learn_checkpoint_score
import dev.ide.ui.generated.resources.learn_checkpoint_title
import dev.ide.ui.generated.resources.learn_done
import dev.ide.ui.generated.resources.learn_discussion
import dev.ide.ui.generated.resources.learn_layout_preview
import dev.ide.ui.editor.preview.LessonLayoutPreview
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
    /**
     * Moves a lesson with exercises into the full editor (its workspace project with the task banner). Called
     * once when such a lesson loads; null keeps every lesson here, which is also the fallback when the
     * workspace could not be opened.
     */
    onOpenInEditor: ((lessonId: String, stepIndex: Int) -> Unit)? = null,
    /** The lesson's discussion (scoped to the current step). Null hides the top-bar button. */
    onOpenDiscussion: ((lessonId: String, stepId: String?) -> Unit)? = null,
) {
    val state = rememberLessonPlayerState(backend, lessonId, initialStep)
    val lesson = state.lesson
    val steps = state.steps
    val step = state.step
    val toEditor = onOpenInEditor != null && steps.any { it is UiLessonStep.Interactive }
    if (toEditor) {
        LaunchedEffect(lesson?.id) { lesson?.let { onOpenInEditor?.invoke(it.id, state.stepIndex) } }
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp), contentAlignment = Alignment.Center) {
            PreparingCard()
        }
        return
    }

    // Ad gating (null on desktop / when hosted without ads). Drives the completion interstitial below.
    val ads = rememberAds()

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            // Top bar: close + progress + step counter.
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButtonCa(CaIcons.close, stringResource(Res.string.close), onExit)
                    Text(
                        lesson?.title.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (steps.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.learn_step_progress, state.stepIndex + 1, steps.size),
                            color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (onOpenDiscussion != null && lesson != null) {
                        androidx.compose.material3.IconButton(onClick = { onOpenDiscussion(lesson.id, step?.id) }) {
                            dev.ide.ui.theme.Symbol(
                                dev.ide.ui.icons.CaSymbols.forum,
                                contentDescription = stringResource(dev.ide.ui.generated.resources.Res.string.learn_discussion),
                                size = 20.dp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ProgressBar(
                    state.progress,
                    track = MaterialTheme.colorScheme.surfaceContainerHighest, fill = MaterialTheme.colorScheme.primary,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.showingSummary) {
                    val (right, total) = state.firstTryScore
                    CheckpointSummary(right, total, Modifier.entrancePop())
                    return@Box
                }
                // Slide steps in the direction of travel (forward on Next, back on Back) with a soft fade.
                AnimatedContent(
                    targetState = state.stepIndex,
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
                        is UiLessonStep.Interactive -> InteractiveStep(s, backend, lessonId, inlayHintsEnabled, host) { state.allowAdvance(true) }
                        is UiLessonStep.Quiz -> QuizStep(s) { correct ->
                            state.recordQuizAnswer(s.id, correct)
                            state.allowAdvance(correct)
                        }
                        null -> {}
                    }
                }
            }

            val isLast = state.isLast
            // Preload the completion interstitial as the learner reaches the final step (once it's solved /
            // answered) so it's ready the instant they tap Finish. Ads-active gated; a no-op on desktop and when
            // ads are off. The show itself happens on Finish below, throttled to every 2nd lesson.
            LaunchedEffect(isLast, state.canAdvance) {
                if (isLast && state.canAdvance && ads?.interstitialsActive == true) ads.host.preloadInterstitial()
            }

            // Bottom navigation bar: Back + Next / Finish.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
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
                        isLast -> stringResource(Res.string.learn_finish)
                        else -> stringResource(Res.string.learn_next)
                    },
                    primary = true,
                    enabled = state.canAdvance && step != null,
                    icon = if (isLast) CaIcons.check else CaIcons.chevronRight,
                ) {
                    state.next {
                        // Every 2nd finished lesson shows the full-screen interstitial (ads-active gated); it
                        // overlays as we return to the track. shouldShowLessonInterstitial advances the counter.
                        if (ads?.shouldShowLessonInterstitial() == true) ads.showInterstitial("lesson")
                        onExit()
                    }
                }
            }
        }
    }
}

// ---- concept step ----

@Composable
internal fun ConceptStep(step: UiLessonStep.Concept, backend: IdeBackend, host: dev.ide.ui.ComposePreviewHost?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(step.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
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
    // An Android layout exercise: XML in the editor, a live preview under it, and a structural check instead
    // of a compile + run. It needs no scratch project, so it skips the preparing gate and the analysis passes.
    val isLayout = step.language == "xml-layout"
    val lang = when {
        isLayout -> CodeLanguage.Xml
        step.language.startsWith("kotlin") -> CodeLanguage.Kotlin
        else -> CodeLanguage.Java
    }
    val fileName = when {
        isLayout -> "activity_main.xml"
        step.language.startsWith("kotlin") -> "Main.kt"
        else -> "Main.java"
    }
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
    var ready by remember(step.id) { mutableStateOf(isLayout) }
    LaunchedEffect(step.id) {
        if (!isLayout) ready = runCatching { backend.learn.prepare(step.language) }.getOrDefault(true)
    }
    // Once ready, watch for (re)indexing so we can show a hint and re-analyze when it settles.
    var indexing by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(step.id, ready) {
        if (!ready || isLayout) return@LaunchedEffect
        while (isActive) {
            indexing = runCatching { backend.learn.indexing(step.language) }.getOrDefault(false)
            delay(700)
        }
    }
    // Live diagnostics + inlay hints: (re)analyze on each settled edit and when indexing finishes; feed the
    // editor's squiggles and the inferred-type/parameter-name hints. Both run against the same buffer snapshot
    // and re-fire when a new edit bumps textRevision (which cancels this pass), so offsets stay consistent.
    // Keyed on the session itself as well as its revision: "Copy to editor" swaps in a new session whose
    // revision counter starts where the old one did, which alone would leave the analysis stale.
    LaunchedEffect(step.id, ready, session, session.textRevision, indexing, inlayHintsEnabled) {
        if (!ready || isLayout) return@LaunchedEffect
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
        Text(step.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
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
                .background(Ide.colors.editorBg).border(1.dp, MaterialTheme.colorScheme.outlineVariant, editorShape),
        ) {
            CodeEditor(
                path = fileName,
                session = session,
                backend = editorBackend,
                modifier = Modifier.fillMaxSize(),
                completionAutoPopup = !isLayout,
            )
        }

        if (isLayout) {
            // Re-render once typing settles rather than on every keystroke.
            var previewXml by remember(step.id) { mutableStateOf(step.starterCode) }
            LaunchedEffect(step.id, session, session.textRevision) {
                delay(LAYOUT_PREVIEW_DEBOUNCE_MS)
                previewXml = session.doc.text
            }
            Text(
                stringResource(Res.string.learn_layout_preview),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            LessonLayoutPreview(xml = previewXml, backend = backend, interactive = false, caption = "", modifier = Modifier.fillMaxWidth())
        }

        // Action row.
        val checkFailedMsg = stringResource(Res.string.learn_check_failed)
        // On a phone the two links and the button do not fit one row, and the Row squeezed the button until its
        // label broke ("Run &" over "Check"). The links take what is left after the button and wrap instead.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                if (step.hints.isNotEmpty() && hintsShown < step.hints.size) {
                    TextAction(stringResource(Res.string.learn_need_hint), CaIcons.lightbulb) { hintsShown++ }
                }
                if (step.solution.isNotBlank() && !showSolution) {
                    TextAction(stringResource(Res.string.learn_show_solution), CaIcons.eye) { showSolution = true }
                }
            }
            PlayerButton(
                when {
                    running && isLayout -> stringResource(Res.string.learn_checking)
                    running -> stringResource(Res.string.learn_running)
                    isLayout -> stringResource(Res.string.learn_check_layout)
                    else -> stringResource(Res.string.learn_run_check)
                },
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
                Text(stringResource(Res.string.learn_solution), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
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
internal fun ResultPanel(result: UiExerciseResult, modifier: Modifier = Modifier) {
    val (icon, tint) = when {
        result.passed -> CaIcons.check to Ide.colors.success
        !result.compiled -> CaIcons.error to MaterialTheme.colorScheme.error
        else -> CaIcons.warning to Ide.colors.warning
    }
    val shape = RoundedCornerShape(Ca.radius.md)
    Column(
        modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.10f)).border(1.dp, tint.copy(alpha = 0.30f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
            Text(result.message, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
        if (result.output.isNotBlank()) {
            Text(stringResource(Res.string.learn_output), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm)).background(Ide.colors.consoleBg)
                    .horizontalScroll(rememberScrollState()).padding(10.dp),
            ) {
                Text(result.output.trimEnd(), color = MaterialTheme.colorScheme.onSurface, style = Ide.type.codeSmall)
            }
        }
        result.diagnostics.forEach { d ->
            Text(d, color = MaterialTheme.colorScheme.error, style = Ide.type.codeSmall)
        }
    }
}

@Composable
internal fun HintCard(number: Int, hint: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.primaryContainer).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), shape).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(CaIcons.lightbulb, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.learn_hint_n, number), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(inlineMarkup(hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- quiz step ----

@Composable
internal fun QuizStep(step: UiLessonStep.Quiz, onAnswered: (correct: Boolean) -> Unit) {
    var selected by remember(step.id) { mutableStateOf(-1) }
    var submitted by remember(step.id) { mutableStateOf(false) }
    val correct = selected == step.correctIndex

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(step.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
        Text(step.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
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
                color = if (correct) Ide.colors.success else Ide.colors.warning, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            )
            if (step.explanation.isNotBlank()) {
                Text(inlineMarkup(step.explanation), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
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
            revealCorrect -> Ide.colors.success
            revealWrong -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        tween(Motion.FAST), label = "opt-border",
    )
    val fill by animateColorAsState(
        when {
            revealCorrect -> Ide.colors.success.copy(alpha = 0.10f)
            revealWrong -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
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
            revealCorrect -> Ide.colors.success
            revealWrong -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }
        Icon(
            if (revealWrong) CaIcons.close else if (revealCorrect || selected) CaIcons.check else CaIcons.dot,
            null, Modifier.size(if (revealWrong || revealCorrect || selected) 18.dp else 8.dp), tint = markTint,
        )
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

// ---- shared buttons ----

@Composable
internal fun PlayerButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    /** The slimmer size for a one-row header (the lesson banner) rather than a bottom action bar. */
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // Animate the fill/label so the primary button lights up smoothly when it becomes enabled (e.g. Next after
    // an exercise passes).
    val bg by animateColorAsState(
        when {
            !primary -> MaterialTheme.colorScheme.surfaceContainerHigh
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        tween(Motion.BASE), label = "btn-bg",
    )
    // The primary fill is the theme's primary (a light lime in the dark theme), so the label is its onPrimary,
    // not white: white on lime read as washed out and failed contrast.
    val fg by animateColorAsState(
        when {
            !primary -> MaterialTheme.colorScheme.onSurface
            enabled -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.outline
        },
        tween(Motion.BASE), label = "btn-fg",
    )
    // Compact is a pill: in a one-row header it sits beside icon buttons, and a squarer 36 dp block read as
    // heavy and crowded the progress bar under it.
    val shape = if (compact) RoundedCornerShape(50) else RoundedCornerShape(Ca.radius.control)
    Row(
        modifier
            .height(if (compact) 32.dp else 44.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(if (compact) 14.dp else 16.dp), tint = fg) }
        Text(
            text,
            color = fg,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
    /** Layout exercises have no scratch engine behind them: the editor is plain, with no completion. */
    private val plain = language == "xml-layout"
    override val editor: EditorService = object : EditorService {
        override fun updateDocument(path: String, text: String) {} // the live buffer is passed per-call
        override fun saveFile(path: String, text: String) {}        // lessons don't persist
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult =
            if (plain) UiCompletionResult(emptyList(), offset, offset) else real.learn.complete(language, text, offset)
        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> =
            if (plain) emptyList() else real.learn.analyze(language, text)
        override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> =
            if (plain) emptyList() else real.learn.hints(language, text, startOffset, endOffset)
    }
}

private const val LAYOUT_PREVIEW_DEBOUNCE_MS = 300L

/**
 * The end of a checkpoint: how many questions were right on the first try, with a line that fits the
 * score. Shown in place of the steps; the bottom bar's Done leaves the lesson.
 */
@Composable
internal fun CheckpointSummary(right: Int, total: Int, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val fraction = if (total == 0) 1f else right.toFloat() / total
    Column(
        modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$right/$total", style = MaterialTheme.typography.headlineSmall, color = c.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Text(
            stringResource(Res.string.learn_checkpoint_title),
            style = MaterialTheme.typography.headlineSmall,
            color = c.onSurface,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            stringResource(Res.string.learn_checkpoint_score, right, total),
            style = MaterialTheme.typography.titleMedium,
            color = c.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(
                when {
                    fraction >= 1f -> Res.string.learn_checkpoint_perfect
                    fraction >= 0.6f -> Res.string.learn_checkpoint_good
                    else -> Res.string.learn_checkpoint_retry
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = c.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Shown while the scratch project's index is building, before the exercise editor is usable. */
@Composable
internal fun PreparingCard() {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Text(stringResource(Res.string.learn_preparing), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(Res.string.learn_preparing_sub),
            color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** A slim "still indexing" hint above the editor once it's usable but the index is still catching up. */
@Composable
private fun IndexingChip() {
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
        Text(stringResource(Res.string.learn_indexing), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun TextAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.control)).clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
