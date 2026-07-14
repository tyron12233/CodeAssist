package dev.ide.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.StepStatus
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContext
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.buildc_build
import dev.ide.ui.generated.resources.stop
import dev.ide.ui.generated.resources.run
import dev.ide.ui.generated.resources.buildc_collapse
import dev.ide.ui.generated.resources.buildc_status_idle
import dev.ide.ui.generated.resources.buildc_status_running
import dev.ide.ui.generated.resources.buildc_status_succeeded
import dev.ide.ui.generated.resources.buildc_status_failed
import dev.ide.ui.generated.resources.buildc_copied
import dev.ide.ui.generated.resources.buildc_copy
import dev.ide.ui.generated.resources.buildc_working
import dev.ide.ui.generated.resources.buildc_tab_problems
import dev.ide.ui.generated.resources.buildc_tab_log
import dev.ide.ui.generated.resources.buildc_tab_steps
import dev.ide.ui.generated.resources.buildc_filter_all
import dev.ide.ui.generated.resources.buildc_filter_errors
import dev.ide.ui.generated.resources.buildc_filter_warnings
import dev.ide.ui.generated.resources.buildc_no_problems
import dev.ide.ui.generated.resources.buildc_no_problems_match
import dev.ide.ui.generated.resources.buildc_ungroup
import dev.ide.ui.generated.resources.buildc_group_by_task
import dev.ide.ui.generated.resources.buildc_empty_log
import dev.ide.ui.generated.resources.buildc_no_log_match
import dev.ide.ui.generated.resources.buildc_general
import dev.ide.ui.generated.resources.buildc_no_steps
import dev.ide.ui.generated.resources.buildc_indexing
import dev.ide.ui.generated.resources.buildc_filter_log
import dev.ide.ui.generated.resources.buildc_loglevel_all
import dev.ide.ui.generated.resources.buildc_loglevel_warnings
import dev.ide.ui.generated.resources.buildc_loglevel_errors
import dev.ide.ui.generated.resources.buildc_step_up_to_date
import dev.ide.ui.generated.resources.buildc_step_no_source
import dev.ide.ui.generated.resources.buildc_step_skipped
import dev.ide.ui.generated.resources.clear
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Build & run console: a persistent header (live status pill, error/warning counts, elapsed time, a
 * Run/Stop control + Copy), a thin running-progress strip, and three tabs over the build's output —
 *  - **Problems**: the structured diagnostics a build streamed, grouped by file with a severity filter and
 *    the captured source snippet; each row jumps to its `file:line` in the editor ([onOpenDiagnostic]).
 *  - **Log**: the raw transcript, grouped by the task that produced each line (collapsible), with a level
 *    filter and a text search.
 *  - **Steps**: the task-engine graph with real per-task status.
 * Surface-agnostic — it draws no background of its own, so it reads correctly both as a persistent right
 * pane and as a phone bottom-sheet. The caller sizes it via [modifier]; the active tab fills the rest.
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
    /** Backend for plugin BOTTOM tool-window tabs; null hides them (e.g. backends/tests without one). */
    backend: IdeBackend? = null,
    activeFilePath: String? = null,
) {
    val running = buildState.status == RunStatus.Running
    val errors = buildState.diagnostics.count { it.severity == UiSeverity.Error }
    val warnings = buildState.diagnostics.count { it.severity == UiSeverity.Warning }
    val done = buildState.steps.count { it.status.isSettled }

    // Plugin-contributed BOTTOM tool windows appear as extra tabs after the built-in three. Additive: the
    // built-in tabs are unchanged. Needs a backend for the tool-window context, so gated on it.
    val pluginTabs = if (backend != null) ToolWindowRegistry.forAnchor(ToolWindowAnchor.BOTTOM) else emptyList()

    var tab by remember { mutableStateOf(BuildTab.Log) }
    var activePluginTab by remember { mutableStateOf<String?>(null) }
    // Pull the errors front-and-center the moment a build fails (but never override the user otherwise).
    LaunchedEffect(buildState.status) {
        if (buildState.status == RunStatus.Failed && (errors > 0 || warnings > 0)) {
            tab = BuildTab.Problems
            activePluginTab = null
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(buildState, errors, warnings, tab, activePluginTab != null, onRun, onStop, onCollapse)
        if (indexStatus.building) IndexingSection(indexStatus)
        buildState.banner?.let { FirstBuildBanner(it) }
        if (running) RunningStrip(buildState.steps)
        ConsoleTabs(
            tab, errors, warnings, done, buildState.steps.size, pluginTabs, activePluginTab,
            onSelect = { tab = it; activePluginTab = null },
            onSelectPlugin = { activePluginTab = it },
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val plugin = pluginTabs.firstOrNull { it.id == activePluginTab }
            if (plugin != null && backend != null) {
                val ctxBackend: IdeBackend = backend
                val ctx = remember(ctxBackend, activeFilePath) {
                    object : ToolWindowContext {
                        override val backend = ctxBackend
                        override val activeFilePath = activeFilePath
                    }
                }
                plugin.content(ctx)
            } else when (tab) {
                BuildTab.Problems -> ProblemsTab(buildState.diagnostics, onOpenDiagnostic)
                BuildTab.Log -> LogTab(buildState.log, running)
                BuildTab.Steps -> StepsTab(buildState.steps)
            }
        }
        // A compact native ad in the console footer, only while nothing is building — a natural pause, never
        // over a running build. Renders nothing unless ads are active.
        if (!running) AdSlot(AdPlacement.BUILD_CONSOLE)
    }
}

private enum class BuildTab { Problems, Log, Steps }

/** True for a step that has settled (won't change again this build) — for the running-progress fraction. */
private val StepStatus.isSettled: Boolean
    get() = this == StepStatus.Done || this == StepStatus.UpToDate ||
            this == StepStatus.NoSource || this == StepStatus.Skipped || this == StepStatus.Failed

// ---------------------------------------------------------------------------
// Header + running strip
// ---------------------------------------------------------------------------

@Composable
private fun Header(
    state: BuildState,
    errors: Int,
    warnings: Int,
    tab: BuildTab,
    pluginActive: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onCollapse: () -> Unit
) {
    val running = state.status == RunStatus.Running
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(CaIcons.terminal, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
        Text(
            stringResource(Res.string.buildc_build),
            color = Ca.colors.textPrimary,
            style = Ca.type.subhead,
            fontWeight = FontWeight.SemiBold
        )
        if (state.moduleName.isNotEmpty()) {
            Text(
                state.moduleName,
                color = Ca.colors.textTertiary,
                style = Ca.type.footnote,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))
        if (errors > 0) MiniCount(CaIcons.error, errors, Ca.colors.error)
        if (warnings > 0) MiniCount(CaIcons.warning, warnings, Ca.colors.warning)
        if (state.elapsedMs > 0 && !running) {
            Text(
                "${state.elapsedMs / 1000.0}s",
                color = Ca.colors.textTertiary,
                style = Ca.type.codeSmall,
                maxLines = 1,
                softWrap = false
            )
        }
        StatusPill(state.status)
        val (copyTab, copyProvide) = copyForTab(state, tab, pluginActive)
        if (copyProvide != null) CopyButton(copyTab, copyProvide)
        if (running) IconButtonCa(
            CaIcons.stop,
            stringResource(Res.string.stop),
            onStop,
            boxSize = 28,
            iconSize = 16,
            tint = Ca.colors.error
        )
        else IconButtonCa(
            CaIcons.play,
            stringResource(Res.string.run),
            onRun,
            boxSize = 28,
            iconSize = 16,
            tint = Ca.colors.run
        )
        IconButtonCa(CaIcons.chevronDown, stringResource(Res.string.buildc_collapse), onCollapse, boxSize = 28, iconSize = 16)
    }
}

/** A small icon + count pill for the header's error/warning summary. */
@Composable
private fun MiniCount(icon: ImageVector, count: Int, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, Modifier.size(12.dp), tint = color)
        Text("$count", color = color, style = Ca.type.caption, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPill(status: RunStatus) {
    val (text, color) = when (status) {
        RunStatus.Idle -> stringResource(Res.string.buildc_status_idle) to Ca.colors.textSecondary
        RunStatus.Running -> stringResource(Res.string.buildc_status_running) to Ca.colors.accent
        RunStatus.Succeeded -> stringResource(Res.string.buildc_status_succeeded) to Ca.colors.run
        RunStatus.Failed -> stringResource(Res.string.buildc_status_failed) to Ca.colors.error
    }
    Chip(text, fill = color.copy(alpha = 0.16f), textColor = color)
}

/**
 * The copy target for the active tab: the whole Problems / Log / Steps transcript as pasteable text. Copying
 * the full set (not just the filtered view) is deliberate — it's the only practical way to capture a build off
 * a device with no `adb`/logcat. Returns a null provider when there's nothing to copy (or a plugin tab owns
 * the pane), which hides the button. The text is built on demand so an empty tap never pays for it.
 */
private fun copyForTab(
    state: BuildState,
    tab: BuildTab,
    pluginActive: Boolean,
): Pair<BuildTab, (() -> String)?> {
    if (pluginActive) return tab to null
    return when (tab) {
        BuildTab.Problems -> tab to (
            if (state.diagnostics.isEmpty()) null
            else fun(): String = renderProblemsForCopy(state.diagnostics))
        BuildTab.Log -> tab to (
            if (state.log.isEmpty()) null
            else fun(): String = state.log.joinToString("\n", transform = ::renderLogForCopy))
        BuildTab.Steps -> tab to (
            if (state.steps.isEmpty()) null
            else fun(): String = renderStepsForCopy(state.steps))
    }
}

/**
 * Copies [provide]'s text to the clipboard in one tap and flips to a check for ~1.5s as confirmation. The
 * text is built lazily on click, so a large transcript costs nothing until the user actually copies it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CopyButton(tab: BuildTab, provide: () -> String) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500.milliseconds); copied = false
        }
    }
    val label = stringResource(
        when (tab) {
            BuildTab.Problems -> Res.string.buildc_tab_problems
            BuildTab.Log -> Res.string.buildc_tab_log
            BuildTab.Steps -> Res.string.buildc_tab_steps
        }
    )
    IconButtonCa(
        if (copied) CaIcons.check else CaIcons.copy,
        if (copied) stringResource(Res.string.buildc_copied) else stringResource(Res.string.buildc_copy, label),
        onClick = {
            scope.launch { clipboard.setText(AnnotatedString(clipForClipboard(provide()))) }
            copied = true
        },
        boxSize = 28,
        iconSize = 16,
        tint = if (copied) Ca.colors.run else Ca.colors.textSecondary,
    )
}

// Android delivers clipboard data over a Binder transaction whose buffer (~1 MB, shared process-wide) a long
// build log overflows, throwing TransactionTooLargeException and taking down the app. Cap the copied text well
// under that, keeping the TAIL (a build's errors and final status live at the end of the log) and noting the
// drop. Not localized: this is diagnostic clipboard payload, like the log lines themselves, not UI chrome.
private const val MAX_CLIPBOARD_CHARS = 200_000

internal fun clipForClipboard(text: String): String {
    if (text.length <= MAX_CLIPBOARD_CHARS) return text
    val dropped = text.length - MAX_CLIPBOARD_CHARS
    return "[... $dropped earlier characters truncated to fit the clipboard ...]\n" +
        text.substring(text.length - MAX_CLIPBOARD_CHARS)
}

private fun renderLogForCopy(l: BuildLogLine): String = buildString {
    if (l.timeLabel.isNotEmpty()) {
        append(l.timeLabel); append(' ')
    }
    if (!l.task.isNullOrEmpty()) {
        append('['); append(l.task); append("] ")
    }
    append(l.message)
}

/** One compiler-style line per diagnostic — `severity: file:line:col: message [source]` + any snippet. */
private fun renderProblemsForCopy(diagnostics: List<BuildDiagnosticUi>): String =
    diagnostics.joinToString("\n", transform = ::renderProblemForCopy)

private fun renderProblemForCopy(d: BuildDiagnosticUi): String = buildString {
    append(
        when (d.severity) {
            UiSeverity.Error -> "error"
            UiSeverity.Warning -> "warning"
            UiSeverity.Info -> "info"
            UiSeverity.Hint -> "hint"
        }
    )
    append(": ")
    d.file?.let { f ->
        append(f)
        if (d.line > 0) {
            append(':'); append(d.line)
            if (d.column > 0) {
                append(':'); append(d.column)
            }
        }
        append(": ")
    }
    append(d.message)
    val source = d.source.ifEmpty { d.kind }
    if (source.isNotEmpty()) {
        append(" ["); append(source); append(']')
    }
    d.detail?.takeIf { it.isNotBlank() }?.let {
        append('\n'); append(it.trimEnd())
    }
}

/** One line per build step — `name  STATUS` — mirroring the Steps tab. */
private fun renderStepsForCopy(steps: List<BuildStepUi>): String =
    steps.joinToString("\n") { "${it.name}  ${stepStatusLabel(it.status)}" }

private fun stepStatusLabel(status: StepStatus): String = when (status) {
    StepStatus.Pending -> "PENDING"
    StepStatus.Running -> "RUNNING"
    StepStatus.Done -> "DONE"
    StepStatus.UpToDate -> "UP-TO-DATE"
    StepStatus.NoSource -> "NO-SOURCE"
    StepStatus.Skipped -> "SKIPPED"
    StepStatus.Failed -> "FAILED"
}

/** A live progress bar over the step graph while a build runs: the current step + a done/total fraction. */
@Composable
private fun RunningStrip(steps: List<BuildStepUi>) {
    if (steps.isEmpty()) return
    val total = steps.size
    val done = steps.count { it.status.isSettled }
    val runningCount = steps.count { it.status == StepStatus.Running }
    val current = steps.firstOrNull { it.status == StepStatus.Running }?.name
    // Count an in-flight step as half-complete so the bar nudges forward the instant a step starts —
    // the running step is represented in the fill, not only in the label. Then tween between targets so
    // the bar glides smoothly instead of snapping each time a step settles.
    val target = ((done + runningCount * 0.5f) / total).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = Motion.BASE, easing = Motion.soft),
        label = "buildProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                current?.let(::shortTask) ?: stringResource(Res.string.buildc_working),
                color = Ca.colors.textSecondary, style = Ca.type.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text("$done/$total", color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = Ca.colors.accent,
            trackColor = Ca.colors.surface2,
        )
    }
}

// ---------------------------------------------------------------------------
// Tab bar
// ---------------------------------------------------------------------------

@Composable
private fun ConsoleTabs(
    tab: BuildTab,
    errors: Int,
    warnings: Int,
    stepsDone: Int,
    stepsTotal: Int,
    pluginTabs: List<ToolWindowContribution>,
    activePluginTab: String?,
    onSelect: (BuildTab) -> Unit,
    onSelectPlugin: (String) -> Unit,
) {
    // A built-in tab reads as selected only while no plugin tab is active.
    val builtInActive = activePluginTab == null
    Column {
        // Horizontally scrollable so the tabs never clip on a narrow sheet / pane.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TabItem(
                stringResource(Res.string.buildc_tab_problems),
                builtInActive && tab == BuildTab.Problems,
                badge = (errors + warnings).takeIf { it > 0 }?.toString(),
                badgeColor = if (errors > 0) Ca.colors.error else Ca.colors.warning
            ) { onSelect(BuildTab.Problems) }
            TabItem(stringResource(Res.string.buildc_tab_log), builtInActive && tab == BuildTab.Log) { onSelect(BuildTab.Log) }
            TabItem(
                stringResource(Res.string.buildc_tab_steps),
                builtInActive && tab == BuildTab.Steps,
                badge = stepsTotal.takeIf { it > 0 }?.let { "$stepsDone/$it" },
                badgeColor = Ca.colors.textTertiary
            ) { onSelect(BuildTab.Steps) }
            // Plugin-contributed BOTTOM tool windows.
            pluginTabs.forEach { tw ->
                TabItem(tw.title, activePluginTab == tw.id) { onSelectPlugin(tw.id) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
    }
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    badge: String? = null,
    badgeColor: Color = Ca.colors.accent,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        // IntrinsicSize.Max gives the column a concrete content width (it sits in a horizontally-scrollable
        // row, so the incoming max width is unbounded) — the selected underline then matches the tab's width.
        Modifier.width(IntrinsicSize.Max)
            .clickable(interactionSource, indication = null, onClick = onClick)
            .padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                label,
                color = if (selected) Ca.colors.textPrimary else Ca.colors.textTertiary,
                style = Ca.type.footnote,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            badge?.let {
                Box(
                    Modifier.background(
                        badgeColor.copy(alpha = 0.18f),
                        RoundedCornerShape(Ca.radius.pill)
                    ).padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        it,
                        color = badgeColor,
                        style = Ca.type.caption2,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(2.dp).background(
                if (selected) Ca.colors.accent else Color.Transparent,
                RoundedCornerShape(Ca.radius.pill)
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Problems tab
// ---------------------------------------------------------------------------

@Composable
private fun ProblemsTab(diagnostics: List<BuildDiagnosticUi>, onOpen: (BuildDiagnosticUi) -> Unit) {
    var filter by remember { mutableStateOf(ProblemFilter.All) }
    val errors = diagnostics.count { it.severity == UiSeverity.Error }
    val warnings = diagnostics.count { it.severity == UiSeverity.Warning }
    val shown = remember(diagnostics, filter) { diagnostics.filter { filter.keep(it.severity) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ConsoleChip(stringResource(Res.string.buildc_filter_all, diagnostics.size), filter == ProblemFilter.All) {
                filter = ProblemFilter.All
            }
            if (errors > 0) ConsoleChip(stringResource(Res.string.buildc_filter_errors, errors), filter == ProblemFilter.Errors) {
                filter = ProblemFilter.Errors
            }
            if (warnings > 0) ConsoleChip(
                stringResource(Res.string.buildc_filter_warnings, warnings),
                filter == ProblemFilter.Warnings
            ) { filter = ProblemFilter.Warnings }
        }
        if (shown.isEmpty()) {
            EmptyState(
                if (diagnostics.isEmpty()) stringResource(Res.string.buildc_no_problems) else stringResource(Res.string.buildc_no_problems_match),
                Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            val groups = remember(shown) { groupByFile(shown) }
            // Selectable so a problem message / captured snippet can be lifted out by hand; the header's
            // Copy button grabs the whole set. Row taps still jump to file:line (tap = click, long-press /
            // drag = select).
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for ((file, items) in groups) {
                        if (file.isNotEmpty()) item { ProblemFileHeader(file, items.size) }
                        items(items) { d -> ProblemRow(d, indented = file.isNotEmpty(), onOpen) }
                    }
                }
            }
        }
    }
}

private enum class ProblemFilter { All, Errors, Warnings }

private fun ProblemFilter.keep(s: UiSeverity): Boolean = when (this) {
    ProblemFilter.All -> true
    ProblemFilter.Errors -> s == UiSeverity.Error
    ProblemFilter.Warnings -> s == UiSeverity.Warning
}

/** Stable grouping: located diagnostics by file (errors-first within), then the un-located bucket last. */
private fun groupByFile(diagnostics: List<BuildDiagnosticUi>): List<Pair<String, List<BuildDiagnosticUi>>> =
    diagnostics.withIndex()
        .sortedWith(compareBy({ it.value.severity != UiSeverity.Error }, { it.index }))
        .groupBy { it.value.file ?: "" }
        .map { (file, items) -> file to items.map { it.value } }
        .sortedBy { it.first.isEmpty() } // "" (General) sinks to the bottom

@Composable
private fun ProblemFileHeader(file: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.file, null, Modifier.size(13.dp), tint = Ca.colors.textTertiary)
        Text(
            file.substringAfterLast('/').substringAfterLast('\\'),
            color = Ca.colors.textSecondary,
            style = Ca.type.caption,
            fontWeight = FontWeight.Medium
        )
        Text("$count", color = Ca.colors.textTertiary, style = Ca.type.caption)
    }
}

@Composable
private fun ProblemRow(
    d: BuildDiagnosticUi,
    indented: Boolean,
    onOpen: (BuildDiagnosticUi) -> Unit
) {
    val clickable = d.file != null
    Column(
        Modifier.fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpen(d) } else Modifier)
            .padding(start = if (indented) 19.dp else 0.dp, top = 3.dp, bottom = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                severityIcon(d.severity),
                null,
                Modifier.size(14.dp).padding(top = 1.dp),
                tint = severityColor(d.severity)
            )
            Text(
                d.message,
                color = Ca.colors.textSecondary,
                style = Ca.type.footnote,
                modifier = Modifier.weight(1f)
            )
            if (d.line > 0) Text(
                ":${d.line}",
                color = Ca.colors.textTertiary,
                style = Ca.type.codeSmall
            )
            Text(
                d.source.ifEmpty { d.kind },
                color = Ca.colors.textTertiary,
                style = Ca.type.caption
            )
        }
        // The captured source snippet / caret context (the compiler's offending line), if any.
        d.detail?.takeIf { it.isNotBlank() }?.let { snippet ->
            Text(
                snippet.trimEnd(),
                color = Ca.colors.textTertiary,
                style = Ca.type.codeSmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
            )
        }
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

// ---------------------------------------------------------------------------
// Log tab
// ---------------------------------------------------------------------------

@Composable
private fun LogTab(log: List<BuildLogLine>, running: Boolean) {
    var level by remember { mutableStateOf(LogLevelFilter.All) }
    var query by remember { mutableStateOf("") }
    var grouped by remember { mutableStateOf(true) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val q = query.trim()
    val filtered = remember(log, level, q) {
        log.filter {
            level.keep(it.level) && (q.isEmpty() || it.message.contains(
                q,
                true
            ) || (it.task?.contains(q, true) == true))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchField(query, { query = it }, Modifier.weight(1f))
            IconButtonCa(
                CaIcons.layers, if (grouped) stringResource(Res.string.buildc_ungroup) else stringResource(Res.string.buildc_group_by_task),
                onClick = { grouped = !grouped }, boxSize = 30, iconSize = 16,
                tint = if (grouped) Ca.colors.accent else Ca.colors.textSecondary,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LogLevelFilter.entries.forEach { f -> ConsoleChip(stringResource(f.label), f == level) { level = f } }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Ca.colors.consoleBg, RoundedCornerShape(Ca.radius.md))
                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
                .padding(10.dp),
        ) {
            if (filtered.isEmpty()) {
                Text(
                    if (log.isEmpty()) stringResource(Res.string.buildc_empty_log) else stringResource(Res.string.buildc_no_log_match),
                    color = Ca.colors.textTertiary, style = Ca.type.codeSmall,
                )
            } else if (grouped) {
                val collapsedKey =
                    collapsed.entries.filter { it.value }.map { it.key }.sorted().joinToString(",")
                val items = remember(filtered, collapsedKey) { buildGroups(filtered, collapsed) }
                LogList(items, running) { task -> collapsed[task] = !(collapsed[task] ?: false) }
            } else {
                val items = remember(filtered) {
                    filtered.map {
                        LogDisplay.Line(
                            it,
                            showTask = true,
                            showTime = true
                        )
                    }
                }
                LogList(items, running) {}
            }
        }
    }
}

private enum class LogLevelFilter(val label: StringResource, val keep: (UiLogLevel) -> Boolean) {
    All(Res.string.buildc_loglevel_all, { true }),
    Warnings(Res.string.buildc_loglevel_warnings, { it == UiLogLevel.Warn || it == UiLogLevel.Error }),
    Errors(Res.string.buildc_loglevel_errors, { it == UiLogLevel.Error }),
}

private sealed interface LogDisplay {
    data class Header(val task: String, val count: Int, val expanded: Boolean) : LogDisplay
    data class Line(val line: BuildLogLine, val showTask: Boolean, val showTime: Boolean) :
        LogDisplay
}

/** Group lines by the task that produced them (first-appearance order); a collapsed group shows only its header. */
private fun buildGroups(
    lines: List<BuildLogLine>,
    collapsed: Map<String, Boolean>
): List<LogDisplay> {
    val groups = LinkedHashMap<String, MutableList<BuildLogLine>>()
    for (l in lines) groups.getOrPut(l.task ?: "") { ArrayList() }.add(l)
    return buildList {
        for ((task, ls) in groups) {
            val expanded = collapsed[task] != true
            add(LogDisplay.Header(task, ls.size, expanded))
            if (expanded) ls.forEach {
                add(
                    LogDisplay.Line(
                        it,
                        showTask = false,
                        showTime = false
                    )
                )
            }
        }
    }
}

@Composable
private fun LogList(items: List<LogDisplay>, running: Boolean, onToggle: (String) -> Unit) {
    val listState = rememberLazyListState()
    // Tail the output while a build runs; once it settles, leave the scroll where the user put it.
    LaunchedEffect(items.size, running) {
        if (running && items.isNotEmpty()) runCatching { listState.animateScrollToItem(items.lastIndex) }
    }
    // Mobile hides the timestamp to reclaim width; drag the rows sideways to peek it (it snaps back).
    // Desktop keeps it inline. See [PeekTimestampReveal].
    PeekTimestampReveal(TimeSlotWidth, Modifier.fillMaxSize()) { reveal, slotPx ->
        LogColumn(items, listState, onToggle, reveal, slotPx)
    }
}

@Composable
private fun LogColumn(
    items: List<LogDisplay>,
    listState: LazyListState,
    onToggle: (String) -> Unit,
    reveal: (() -> Float)?,
    slotPx: Float,
) {
    SelectionContainer {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items) { _, item ->
                when (item) {
                    is LogDisplay.Header -> LogGroupHeader(
                        item.task,
                        item.count,
                        item.expanded
                    ) { onToggle(item.task) }

                    is LogDisplay.Line -> LogLineRow(
                        item.line,
                        item.showTask,
                        item.showTime,
                        reveal,
                        slotPx
                    )
                }
            }
        }
    }
}

@Composable
private fun LogGroupHeader(task: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (expanded) CaIcons.caretDown else CaIcons.caretRight,
            null,
            Modifier.size(12.dp),
            tint = Ca.colors.textTertiary
        )
        Text(
            task.ifEmpty { stringResource(Res.string.buildc_general) },
            color = Ca.colors.textSecondary,
            style = Ca.type.codeSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

/** Width reserved for the peek-on-drag timestamp gutter (mobile). */
private val TimeSlotWidth = 84.dp

@Composable
private fun LogLineRow(
    line: BuildLogLine,
    showTask: Boolean,
    showTime: Boolean,
    reveal: (() -> Float)?,
    slotPx: Float,
) {
    if (reveal != null) {
        // Mobile: the timestamp is parked off the left edge and slides in as the row is dragged right,
        // so it costs no horizontal space at rest. [showTime] is moot here — the drag is the reveal.
        Box(Modifier.fillMaxWidth().padding(start = 14.dp, top = 1.dp, bottom = 1.dp)) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { translationX = reveal() },
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showTask && !line.task.isNullOrEmpty()) Text(
                    shortTask(line.task!!),
                    color = Ca.colors.accent.copy(alpha = 0.85f),
                    style = Ca.type.caption2,
                    maxLines = 1,
                )
                Text(
                    line.message,
                    color = logColor(line.level, line.message),
                    style = Ca.type.codeSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (line.timeLabel.isNotEmpty()) Text(
                line.timeLabel,
                color = Ca.colors.textTertiary,
                style = Ca.type.caption2,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart)
                    .graphicsLayer { translationX = reveal() - slotPx },
            )
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showTime && line.timeLabel.isNotEmpty()) Text(
            line.timeLabel,
            color = Ca.colors.textTertiary,
            style = Ca.type.caption2
        )
        if (showTask && !line.task.isNullOrEmpty()) {
            Text(
                shortTask(line.task!!),
                color = Ca.colors.accent.copy(alpha = 0.85f),
                style = Ca.type.caption2,
                maxLines = 1
            )
        }
        Text(
            line.message,
            color = logColor(line.level, line.message),
            style = Ca.type.codeSmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun logColor(level: UiLogLevel, message: String): Color = when (level) {
    UiLogLevel.Error -> Ca.colors.error
    UiLogLevel.Warn -> Ca.colors.warning
    UiLogLevel.Debug -> Ca.colors.textTertiary
    UiLogLevel.Info -> if (message.startsWith("> ")) Ca.colors.accent else Ca.colors.textSecondary
}

/** ":app:compileJava" → "compileJava" — the leaf step name, for compact line/strip labels. */
private fun shortTask(task: String): String = task.substringAfterLast(':').ifEmpty { task }

// ---------------------------------------------------------------------------
// Steps tab
// ---------------------------------------------------------------------------

@Composable
private fun StepsTab(steps: List<BuildStepUi>) {
    if (steps.isEmpty()) {
        EmptyState(stringResource(Res.string.buildc_no_steps), Modifier.fillMaxSize())
        return
    }
    SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(steps) { StepRow(it) }
        }
    }
}

@Composable
private fun StepRow(step: BuildStepUi) {
    val running = step.status == StepStatus.Running
    // A slow breathing accent tint marks the row that's executing right now (the spinner icon already
    // turns); together they make the active step unmistakable in the list.
    val tint = if (running) runningPulseAlpha() else 0f
    Row(
        Modifier.fillMaxWidth()
            .background(Ca.colors.accent.copy(alpha = tint), RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusIcon(step.status)
        Text(
            step.name,
            color = when {
                running -> Ca.colors.textPrimary
                step.status == StepStatus.Pending -> Ca.colors.textSecondary.copy(alpha = 0.55f)
                else -> Ca.colors.textSecondary
            },
            style = Ca.type.footnote,
            fontWeight = FontWeight.Medium.takeIf { running },
        )
        // The "why it didn't run" tag, Gradle-style: UP-TO-DATE / NO-SOURCE / SKIPPED, dimmed at the right.
        statusTag(step.status)?.let { tag ->
            Spacer(Modifier.weight(1f))
            Text(stringResource(tag), color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
    }
}

/** A slow breathing alpha (~0.05 ↔ 0.15) for the currently-running step's row tint. */
@Composable
private fun runningPulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "stepPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = Motion.soft),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stepPulseAlpha",
    )
    return alpha
}

private fun statusTag(status: StepStatus): StringResource? = when (status) {
    StepStatus.UpToDate -> Res.string.buildc_step_up_to_date
    StepStatus.NoSource -> Res.string.buildc_step_no_source
    StepStatus.Skipped -> Res.string.buildc_step_skipped
    else -> null
}

@Composable
private fun StatusIcon(status: StepStatus) {
    when (status) {
        StepStatus.Pending ->
            Box(
                Modifier.size(15.dp)
                    .border(1.5.dp, Ca.colors.separatorStrong, RoundedCornerShape(Ca.radius.pill))
            )

        StepStatus.Running ->
            CircularProgressIndicator(
                Modifier.size(14.dp),
                color = Ca.colors.accent,
                strokeWidth = 2.dp
            )

        StepStatus.Done -> Icon(CaIcons.check, null, Modifier.size(15.dp), tint = Ca.colors.run)
        // up-to-date = real (cached) result, a muted check; no-source/skipped = no work, a faint dot.
        StepStatus.UpToDate -> Icon(
            CaIcons.check,
            null,
            Modifier.size(15.dp),
            tint = Ca.colors.textTertiary
        )

        StepStatus.NoSource, StepStatus.Skipped ->
            Icon(CaIcons.dot, null, Modifier.size(15.dp), tint = Ca.colors.textTertiary)

        StepStatus.Failed -> Icon(CaIcons.error, null, Modifier.size(15.dp), tint = Ca.colors.error)
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

/**
 * An informational notice above the tabs — currently the first-build warning that dexing has no cache yet,
 * so this build is slower and the next one will be much faster. Quiet accent styling: it's reassurance.
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
private fun IndexingSection(status: IndexUiStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(CaIcons.layers, null, Modifier.size(15.dp), tint = Ca.colors.accent)
            Text(
                status.message.ifEmpty { stringResource(Res.string.buildc_indexing) },
                color = Ca.colors.textSecondary,
                style = Ca.type.footnote
            )
            if (status.fraction in 0.0..1.0) {
                Spacer(Modifier.weight(1f))
                Text(
                    "${(status.fraction * 100).toInt()}%",
                    color = Ca.colors.textTertiary,
                    style = Ca.type.caption
                )
            }
        }
        if (status.fraction in 0.0..1.0) {
            LinearProgressIndicator(
                progress = { status.fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Ca.colors.accent,
                trackColor = Ca.colors.surface2
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Ca.colors.accent,
                trackColor = Ca.colors.surface2
            )
        }
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = Ca.colors.textTertiary, style = Ca.type.footnote)
    }
}

/** A pill toggle for the Problems severity / Log level filters. */
@Composable
private fun ConsoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .background(
                if (selected) Ca.colors.accent else Ca.colors.surface2,
                RoundedCornerShape(Ca.radius.pill)
            )
            .border(
                1.dp,
                if (selected) Color.Transparent else Ca.colors.hairline,
                RoundedCornerShape(Ca.radius.pill)
            )
            .clickable(interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            color = if (selected) Ca.colors.textOnAccent else Ca.colors.textSecondary,
            style = Ca.type.caption,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The Log tab's text filter. */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier.background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(14.dp), tint = Ca.colors.accent)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(
                stringResource(Res.string.buildc_filter_log),
                color = Ca.colors.textTertiary,
                style = Ca.type.footnote
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                CaIcons.close,
                stringResource(Res.string.clear),
                Modifier.size(14.dp)
                    .clickable(interactionSource, indication = null) { onValueChange("") },
                tint = Ca.colors.textTertiary
            )
        }
    }
}
