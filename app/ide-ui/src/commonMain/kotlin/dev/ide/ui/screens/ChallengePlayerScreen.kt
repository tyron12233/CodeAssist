package dev.ide.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiChallengeDay
import dev.ide.ui.backend.UiChallengeLanguage
import dev.ide.ui.backend.UiChallengeProblem
import dev.ide.ui.backend.UiChallengeSample
import dev.ide.ui.backend.UiChallengeVerdict
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiLocalRun
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.markdown.Markdown
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Motion
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.tonalPair
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The solve screen.
 *
 * Two actions sit at the bottom and they are deliberately different weights. Run compiles and runs on
 * this device against the published examples: it is fast, unlimited, and decides nothing. Submit sends
 * the source to the server, which is the only thing that can produce a verdict, and it costs an attempt.
 *
 * The editor is the app's own, wired to a hidden scratch project, so a challenge gets the same
 * completion, diagnostics, inlay hints and folding as real code.
 */
@Composable
fun ChallengePlayerScreen(
    backend: IdeBackend,
    date: String,
    inlayHintsEnabled: Boolean,
    onExit: () -> Unit,
    onSolved: () -> Unit,
) {
    var day by remember(date) { mutableStateOf<UiChallengeDay?>(null) }
    LaunchedEffect(date) {
        day = runCatching { backend.challenges.day(date) }.getOrNull()
        // Starting the clock is a server call and the server keeps the first answer, so a reopen does not
        // reset it. It happens here rather than on the tab: browsing should not start a timer.
        if (day?.scheduled == true) runCatching { backend.challenges.start(date) }
    }

    val problem = day?.problem
    if (problem == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        }
        return
    }

    Solver(
        backend = backend,
        date = date,
        day = day!!,
        problem = problem,
        inlayHintsEnabled = inlayHintsEnabled,
        onExit = onExit,
        onSolved = onSolved,
    )
}

private enum class SolverPane { Problem, Code }

@Composable
private fun Solver(
    backend: IdeBackend,
    date: String,
    day: UiChallengeDay,
    problem: UiChallengeProblem,
    inlayHintsEnabled: Boolean,
    onExit: () -> Unit,
    onSolved: () -> Unit,
) {
    var pane by remember(problem.id) { mutableStateOf(SolverPane.Problem) }
    var language by remember(problem.id) {
        mutableStateOf(day.you.language ?: problem.featuredLanguage)
    }
    val material = problem.materials[language]
    var session by remember(problem.id, language) {
        mutableStateOf(EditorSession(material?.stub.orEmpty(), language.toEditorLanguage()))
    }

    val scope = rememberCoroutineScope()
    var localRun by remember(problem.id) { mutableStateOf<UiLocalRun?>(null) }
    var running by remember(problem.id) { mutableStateOf(false) }
    var submitting by remember(problem.id) { mutableStateOf(false) }
    var verdict by remember(problem.id) { mutableStateOf<UiChallengeVerdict?>(null) }

    val editorBackend = remember(backend, language) { ChallengeEditorBackend(backend, language) }

    // The scratch project's index is built before the editor is usable, so completion works from the
    // first keystroke rather than after a pause nobody can explain.
    var ready by remember(language) { mutableStateOf(false) }
    LaunchedEffect(language) {
        ready = runCatching { backend.challenges.prepare(language) }.getOrDefault(true)
    }
    var indexing by remember(language) { mutableStateOf(false) }
    LaunchedEffect(language, ready) {
        if (!ready) return@LaunchedEffect
        while (isActive) {
            indexing = runCatching { backend.challenges.indexing(language) }.getOrDefault(false)
            delay(700)
        }
    }

    // Diagnostics, hints and folds on each settled edit, against the same buffer snapshot. A new edit
    // cancels this pass, so the offsets a result carries always match the text it was computed from.
    LaunchedEffect(problem.id, language, ready, session.textRevision, indexing, inlayHintsEnabled) {
        if (!ready) return@LaunchedEffect
        delay(250)
        val code = session.doc.text
        session.applyAnalysis(runCatching { backend.challenges.analyze(language, code) }.getOrDefault(emptyList()))
        session.applyInlayHints(
            if (!inlayHintsEnabled) emptyList()
            else runCatching { backend.challenges.hints(language, code, 0, code.length) }.getOrDefault(emptyList()),
        )
        session.applyCodeFolds(
            runCatching { backend.challenges.folds(language, code) }.getOrDefault(emptyList())
                .map { FoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault) },
        )
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        SolverTopBar(problem = problem, startedAtMs = day.you.startedAtMs, onExit = onExit)
        PaneSwitch(pane) { pane = it }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (pane) {
                SolverPane.Problem -> ProblemPane(problem)
                SolverPane.Code -> CodePane(
                    problem = problem,
                    language = language,
                    onLanguage = { language = it },
                    session = session,
                    editorBackend = editorBackend,
                    ready = ready,
                    indexing = indexing,
                    localRun = localRun,
                    onResetStub = { session = EditorSession(material?.stub.orEmpty(), language.toEditorLanguage()) },
                )
            }
        }

        ActionBar(
            running = running,
            submitting = submitting,
            canSubmit = day.you.signedIn,
            onRun = {
                localRun = null
                running = true
                scope.launch {
                    localRun = runCatching {
                        backend.challenges.run(language, session.doc.text, problem)
                    }.getOrElse { UiLocalRun(diagnostics = listOf(it.message ?: "The run failed.")) }
                    running = false
                    pane = SolverPane.Code
                }
            },
            onSubmit = {
                submitting = true
                scope.launch {
                    val result = runCatching {
                        backend.challenges.submit(date, language, session.doc.text)
                    }.getOrElse { UiChallengeVerdict(error = it.message ?: "The judge could not be reached.") }
                    submitting = false
                    verdict = result
                    if (result.accepted) onSolved()
                }
            },
        )
    }

    ChallengeResultSheet(
        verdict = verdict,
        problem = problem,
        onDismiss = { verdict = null },
        onBackToTab = { verdict = null; onExit() },
    )
}

@Composable
private fun SolverTopBar(problem: UiChallengeProblem, startedAtMs: Long, onExit: () -> Unit) {
    val now = rememberSecondsTicker()
    val elapsed = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0L
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(Ca.radius.pill)).clickable(onClick = onExit),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(CaSymbols.arrowBack, contentDescription = "Back", size = 22.dp, tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                problem.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                problem.difficulty.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (elapsed > 0) {
            Row(
                Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(CaSymbols.schedule, contentDescription = null, size = 15.dp, tint = MaterialTheme.colorScheme.outline)
                Text(
                    formatSolveTime(elapsed),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PaneSwitch(selected: SolverPane, onSelect: (SolverPane) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(Ca.radius.pill))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SolverPane.entries.forEach { option ->
            val active = option == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(Ca.radius.pill))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (option == SolverPane.Problem) "Problem" else "Code",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ProblemPane(problem: UiChallengeProblem) {
    var hintShown by remember(problem.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Markdown(problem.statement)

        problem.constraints?.takeIf { it.isNotBlank() }?.let { constraints ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Constraints",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Markdown(constraints, paragraphStyle = MaterialTheme.typography.bodyMedium)
            }
        }

        if (problem.samples.isNotEmpty()) {
            Text(
                "Examples",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            problem.samples.forEach { SampleCard(it) }
        }

        problem.hint?.takeIf { it.isNotBlank() }?.let { hint ->
            if (hintShown) {
                val pair = tonalPair(2)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(pair.container).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Symbol(CaSymbols.info, contentDescription = null, size = 18.dp, tint = pair.onContainer)
                    Text(hint, style = MaterialTheme.typography.bodyMedium, color = pair.onContainer)
                }
            } else {
                OutlinedButton(onClick = { hintShown = true }, shape = RoundedCornerShape(Ca.radius.pill)) {
                    Text("Show a hint")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SampleCard(sample: UiChallengeSample) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MonoLine("Input", sample.inputJson)
        MonoLine("Output", sample.expectedJson)
        sample.explanation?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun MonoLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(52.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodePane(
    problem: UiChallengeProblem,
    language: UiChallengeLanguage,
    onLanguage: (UiChallengeLanguage) -> Unit,
    session: EditorSession,
    editorBackend: IdeBackend,
    ready: Boolean,
    indexing: Boolean,
    localRun: UiLocalRun?,
    onResetStub: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            problem.materials.keys.sortedBy { it.ordinal }.forEach { option ->
                val active = option == language
                Text(
                    option.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clip(RoundedCornerShape(Ca.radius.pill))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onLanguage(option) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Reset",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.clickable(onClick = onResetStub).padding(6.dp),
            )
        }

        AnimatedVisibility(
            visible = indexing,
            enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST)),
        ) {
            Text(
                "Indexing, suggestions are still catching up",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        val shape = RoundedCornerShape(Ca.radius.md)
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(shape).background(Ide.colors.editorBg)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            if (!ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                        Text(
                            "Preparing your workspace",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else {
                CodeEditor(
                    path = if (language == UiChallengeLanguage.Kotlin) "Solution.kt" else "Solution.java",
                    session = session,
                    backend = editorBackend,
                    modifier = Modifier.fillMaxSize(),
                    completionAutoPopup = true,
                )
            }
        }

        if (localRun != null) LocalRunStrip(localRun)
    }
}

/**
 * What the device run found.
 *
 * Deliberately phrased as examples rather than tests: passing these says the solution handles the cases
 * everyone can see, and nothing about the ones that decide the verdict.
 */
@Composable
private fun LocalRunStrip(run: UiLocalRun) {
    val failed = run.outcomes.filter { !it.passed }
    val pair = when {
        !run.compiled -> tonalPair(2)
        run.passed -> tonalPair(1)
        else -> tonalPair(2)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.md)).background(pair.container).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symbol(
                if (run.passed) CaSymbols.checkCircle else CaSymbols.error,
                contentDescription = null,
                size = 18.dp,
                tint = pair.onContainer,
                filled = true,
            )
            Text(
                when {
                    !run.compiled -> "Did not compile"
                    run.passed -> "All " + run.outcomes.size + " examples pass"
                    else -> failed.size.toString() + " of " + run.outcomes.size + " examples fail"
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = pair.onContainer,
            )
            Spacer(Modifier.weight(1f))
            Text(
                run.elapsedMs.toString() + " ms",
                style = MaterialTheme.typography.labelSmall,
                color = pair.onContainer.copy(alpha = 0.7f),
            )
        }
        if (!run.compiled) {
            run.diagnostics.take(3).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = pair.onContainer.copy(alpha = 0.85f),
                )
            }
        } else {
            failed.take(2).forEach { outcome ->
                Text(
                    "Example " + (outcome.idx + 1) + ": expected " + outcome.expectedJson +
                        (outcome.error?.let { ", threw " + it } ?: (", got " + (outcome.actualJson ?: "nothing"))),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = pair.onContainer.copy(alpha = 0.85f),
                )
            }
            if (run.passed) {
                Text(
                    "Hidden tests decide the verdict. Submit when you are ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = pair.onContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    running: Boolean,
    submitting: Boolean,
    canSubmit: Boolean,
    onRun: () -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onRun,
            enabled = !running && !submitting,
            shape = RoundedCornerShape(Ca.radius.pill),
            modifier = Modifier.weight(1f).height(50.dp),
        ) {
            if (running) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Symbol(CaSymbols.playArrow, contentDescription = null, size = 18.dp, filled = true)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Running" else "Run examples")
        }
        Button(
            onClick = onSubmit,
            enabled = canSubmit && !running && !submitting,
            shape = RoundedCornerShape(Ca.radius.pill),
            modifier = Modifier.weight(1f).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Symbol(CaSymbols.bolt, contentDescription = null, size = 18.dp, filled = true)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (submitting) "Judging" else "Submit", fontWeight = FontWeight.Bold)
        }
    }
}

private fun UiChallengeLanguage.toEditorLanguage(): CodeLanguage =
    if (this == UiChallengeLanguage.Kotlin) CodeLanguage.Kotlin else CodeLanguage.Java

/**
 * Routes the embedded editor's language services to the challenge scratch project.
 *
 * A standalone [EditorService] rather than a delegate: with no project open, the real editor's methods
 * raise "No project is open", and a challenge is solved from the home screen where that is the normal
 * state.
 */
private class ChallengeEditorBackend(
    private val real: IdeBackend,
    private val language: UiChallengeLanguage,
) : IdeBackend by real {
    override val editor: EditorService = object : EditorService {
        override fun updateDocument(path: String, text: String) {}
        override fun saveFile(path: String, text: String) {}
        override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult =
            real.challenges.complete(language, text, offset)
        override suspend fun analyze(path: String, text: String): List<UiDiagnostic> =
            real.challenges.analyze(language, text)
        override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> =
            real.challenges.hints(language, text, startOffset, endOffset)
    }
}
