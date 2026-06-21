package dev.ide.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * Build & run console content: a header with a live status pill + elapsed time and a Run/Stop
 * control; the task-engine step graph with real per-task status; and a solid console-bg log body that
 * streams compiler output and the program's stdout. Surface-agnostic — it draws no background of its
 * own, so it reads correctly both as a persistent right pane (wrapped in a regular-glass
 * [GlassSurface]) and as the content of a glass-thick [BottomSheet] on phone. The caller sizes it via
 * [modifier]; the log body fills the remaining height in either host.
 */
@Composable
fun BuildConsole(
    buildState: BuildState,
    indexStatus: IndexUiStatus,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDiagnostic: (BuildDiagnosticUi) -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(buildState, onRun, onStop, onCollapse)
        IndexingSection(indexStatus)
        buildState.banner?.let { FirstBuildBanner(it) }
        StepGraph(buildState.steps, buildState.status)
        ProblemsSection(buildState.diagnostics, onOpenDiagnostic)
        LogBody(buildState.log)
    }
}

/**
 * The structured diagnostics a build streamed (see [BuildDiagnosticUi]), grouped by file with a
 * severity-count summary — the build console's "Problems" view. Each row is click-to-open at its line.
 * Errors sort first; un-located tool messages (a dexer summary) land under a "General" bucket. Like the
 * step graph it's collapsible + height-capped so the raw log below always keeps its space; it
 * auto-expands when the build fails so the errors are foremost. Empty diagnostics → renders nothing.
 */
@Composable
private fun ProblemsSection(diagnostics: List<BuildDiagnosticUi>, onOpen: (BuildDiagnosticUi) -> Unit) {
    if (diagnostics.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    val errors = diagnostics.count { it.severity == UiSeverity.Error }
    val warnings = diagnostics.count { it.severity == UiSeverity.Warning }
    // Stable grouping: located diagnostics by file (errors-first within), then the un-located bucket last.
    val groups = remember(diagnostics) {
        diagnostics.withIndex()
            .sortedWith(compareBy({ it.value.severity != UiSeverity.Error }, { it.index }))
            .groupBy { it.value.file ?: "" }
            .map { (file, items) -> file to items.map { it.value } }
            .sortedBy { it.first.isEmpty() } // "" (General) sinks to the bottom
    }

    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (expanded) CaIcons.caretDown else CaIcons.caretRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
            Text("Problems", color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (errors > 0) Text("$errors error${if (errors == 1) "" else "s"}", color = Ca.colors.error, style = Ca.type.caption)
            if (warnings > 0) Text("$warnings warning${if (warnings == 1) "" else "s"}", color = Ca.colors.warning, style = Ca.type.caption)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((file, items) in groups) {
                    if (file.isNotEmpty()) ProblemFileHeader(file, items.size)
                    for (d in items) ProblemRow(d, indented = file.isNotEmpty(), onOpen)
                }
            }
        }
    }
}

@Composable
private fun ProblemFileHeader(file: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.file, null, Modifier.size(13.dp), tint = Ca.colors.textTertiary)
        Text(file.substringAfterLast('/').substringAfterLast('\\'), color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Medium)
        Text("$count", color = Ca.colors.textTertiary, style = Ca.type.caption)
    }
}

@Composable
private fun ProblemRow(d: BuildDiagnosticUi, indented: Boolean, onOpen: (BuildDiagnosticUi) -> Unit) {
    val clickable = d.file != null
    Row(
        Modifier.fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpen(d) } else Modifier)
            .padding(start = if (indented) 19.dp else 0.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(severityIcon(d.severity), null, Modifier.size(14.dp).padding(top = 1.dp), tint = severityColor(d.severity))
        Text(d.message, color = Ca.colors.textSecondary, style = Ca.type.footnote, modifier = Modifier.weight(1f))
        if (d.line > 0) Text(":${d.line}", color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
        Text(d.source.ifEmpty { d.kind }, color = Ca.colors.textTertiary, style = Ca.type.caption)
    }
}

@Composable
private fun severityIcon(s: UiSeverity) = when (s) {
    UiSeverity.Error -> CaIcons.error
    UiSeverity.Warning -> CaIcons.warning
    else -> CaIcons.info
}

@Composable
private fun severityColor(s: UiSeverity): Color = when (s) {
    UiSeverity.Error -> Ca.colors.error
    UiSeverity.Warning -> Ca.colors.warning
    else -> Ca.colors.accent
}

/**
 * An informational notice above the step graph — currently the first-build warning that dexing has no
 * cache yet, so this build is slower and the next one will be much faster. Quiet accent styling: it's
 * reassurance, not an error.
 */
@Composable
private fun FirstBuildBanner(text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(Ca.colors.accent.copy(alpha = 0.12f), RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(Ca.radius.md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.info, null, Modifier.size(16.dp), tint = Ca.colors.accent)
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.footnote)
    }
}

@Composable
private fun Header(state: BuildState, onRun: () -> Unit, onStop: () -> Unit, onCollapse: () -> Unit) {
    val running = state.status == RunStatus.Running
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(CaIcons.terminal, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
        Text("Build", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        if (state.moduleName.isNotEmpty()) Text(state.moduleName, color = Ca.colors.textTertiary, style = Ca.type.footnote)
        Spacer(Modifier.weight(1f))
        if (state.elapsedMs > 0 && !running) {
            Text("${state.elapsedMs / 1000.0}s", color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
        }
        StatusPill(state.status)
        if (state.log.isNotEmpty()) CopyLogButton(state.log)
        if (running) IconButtonCa(CaIcons.stop, "Stop", onStop, boxSize = 28, iconSize = 16, tint = Ca.colors.error)
        else IconButtonCa(CaIcons.play, "Run", onRun, boxSize = 28, iconSize = 16, tint = Ca.colors.run)
        IconButtonCa(CaIcons.chevronDown, "Collapse", onCollapse, boxSize = 28, iconSize = 16)
    }
}

/**
 * Copies the whole console log to the clipboard in one tap — the only practical way to capture a build
 * failure off a device with no `adb`/logcat. Flips to a check for ~1.5s as confirmation.
 */
@Composable
private fun CopyLogButton(log: List<String>) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    IconButtonCa(
        if (copied) CaIcons.check else CaIcons.copy,
        if (copied) "Log copied" else "Copy log",
        onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))); copied = true },
        boxSize = 28,
        iconSize = 16,
        tint = if (copied) Ca.colors.run else Ca.colors.textSecondary,
    )
}

@Composable
private fun StatusPill(status: RunStatus) {
    val (text, color) = when (status) {
        RunStatus.Idle -> "Idle" to Ca.colors.textSecondary
        RunStatus.Running -> "Running…" to Ca.colors.accent
        RunStatus.Succeeded -> "Succeeded" to Ca.colors.run
        RunStatus.Failed -> "Failed" to Ca.colors.error
    }
    Chip(text, fill = color.copy(alpha = 0.16f), textColor = color)
}

/**
 * The task-engine step graph, as a collapsible section. A long graph used to render in full above the log
 * and squeeze it off-screen on phones; now it (a) collapses to a one-line summary the user can toggle, and
 * (b) even expanded is height-capped + internally scrollable, so the log below always keeps its space.
 * Auto-collapses once on failure so the error output is foremost.
 */
@Composable
private fun StepGraph(steps: List<BuildStepUi>, status: RunStatus) {
    if (steps.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(status) { if (status == RunStatus.Failed) expanded = false }

    val done = steps.count { it.status == StepStatus.Done || it.status == StepStatus.UpToDate }
    val failed = steps.count { it.status == StepStatus.Failed }

    Column {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (expanded) CaIcons.caretDown else CaIcons.caretRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
            Text("Build steps", color = Ca.colors.textSecondary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (failed > 0) Text("$failed failed", color = Ca.colors.error, style = Ca.type.caption)
            Text("$done/${steps.size}", color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.heightIn(max = 168.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (step in steps) StepRow(step)
            }
        }
    }
}

@Composable
private fun StepRow(step: BuildStepUi) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusIcon(step.status)
        Text(
            step.name,
            color = if (step.status == StepStatus.Pending) Ca.colors.textSecondary.copy(alpha = 0.55f) else Ca.colors.textSecondary,
            style = Ca.type.footnote,
        )
        // The "why it didn't run" tag, Gradle-style: UP-TO-DATE / NO-SOURCE / SKIPPED, dimmed at the right.
        statusTag(step.status)?.let { tag ->
            Spacer(Modifier.weight(1f))
            Text(tag, color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
    }
}

private fun statusTag(status: StepStatus): String? = when (status) {
    StepStatus.UpToDate -> "UP-TO-DATE"
    StepStatus.NoSource -> "NO-SOURCE"
    StepStatus.Skipped -> "SKIPPED"
    else -> null
}

@Composable
private fun StatusIcon(status: StepStatus) {
    when (status) {
        StepStatus.Pending ->
            Box(Modifier.size(15.dp).border(1.5.dp, Ca.colors.separatorStrong, RoundedCornerShape(Ca.radius.pill)))
        StepStatus.Running ->
            CircularProgressIndicator(Modifier.size(14.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
        StepStatus.Done -> Icon(CaIcons.check, null, Modifier.size(15.dp), tint = Ca.colors.run)
        // up-to-date = real (cached) result, a muted check; no-source/skipped = no work, a faint dot.
        StepStatus.UpToDate -> Icon(CaIcons.check, null, Modifier.size(15.dp), tint = Ca.colors.textTertiary)
        StepStatus.NoSource, StepStatus.Skipped ->
            Icon(CaIcons.dot, null, Modifier.size(15.dp), tint = Ca.colors.textTertiary)
        StepStatus.Failed -> Icon(CaIcons.error, null, Modifier.size(15.dp), tint = Ca.colors.error)
    }
}

@Composable
private fun ColumnScope.LogBody(log: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    Box(
        Modifier.fillMaxWidth().weight(1f)
            .background(Ca.colors.consoleBg, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .padding(12.dp),
    ) {
        if (log.isEmpty()) {
            Text("Press Run to build & run this module.", color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
        } else {
            // Selectable so a user can also drag-select part of the output (the header button copies it all).
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(log) { line -> Text(line, color = logColor(line), style = Ca.type.codeSmall) }
                }
            }
        }
    }
}

@Composable
private fun logColor(line: String): Color = when {
    line.startsWith("FAILED") || line.contains("error:") || line.contains("Exception") -> Ca.colors.error
    line.startsWith("> ") -> Ca.colors.accent
    else -> Ca.colors.textSecondary
}

@Composable
private fun IndexingSection(status: IndexUiStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.layers, null, Modifier.size(15.dp), tint = if (status.building) Ca.colors.accent else Ca.colors.success)
            Text(
                if (status.building) status.message.ifEmpty { "Indexing…" } else "Index ready",
                color = Ca.colors.textSecondary,
                style = Ca.type.footnote,
            )
            if (status.building && status.fraction in 0.0..1.0) {
                Spacer(Modifier.weight(1f))
                Text("${(status.fraction * 100).toInt()}%", color = Ca.colors.textTertiary, style = Ca.type.caption)
            }
        }
        if (status.building) {
            if (status.fraction in 0.0..1.0) {
                LinearProgressIndicator(progress = { status.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().height(3.dp), color = Ca.colors.accent, trackColor = Ca.colors.surface2)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Ca.colors.accent, trackColor = Ca.colors.surface2)
            }
        }
    }
}
