package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiTestCase
import dev.ide.ui.backend.UiTestRun
import dev.ide.ui.backend.UiTestStatus
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.run_tab_output
import dev.ide.ui.generated.resources.run_tab_tests
import dev.ide.ui.generated.resources.tests_class_failed
import dev.ide.ui.generated.resources.tests_failed
import dev.ide.ui.generated.resources.tests_none_found
import dev.ide.ui.generated.resources.tests_passed
import dev.ide.ui.generated.resources.tests_progress
import dev.ide.ui.generated.resources.tests_rerun_failed
import dev.ide.ui.generated.resources.tests_run_class
import dev.ide.ui.generated.resources.tests_run_one
import dev.ide.ui.generated.resources.tests_skipped
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.touchInput
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/** What the Run screen shows of a test run: its results, or the program output beside them. */
internal enum class RunView { Tests, Output }

/** The Tests / Output switch above a test run's content. */
@Composable
internal fun RunViewTabs(selected: RunView, tests: UiTestRun, onSelect: (RunView) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.pill))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (view in RunView.entries) {
            val on = view == selected
            val label = when (view) {
                RunView.Tests -> stringResource(Res.string.run_tab_tests) + if (tests.cases.isNotEmpty()) "  ${tests.cases.size}" else ""
                RunView.Output -> stringResource(Res.string.run_tab_output)
            }
            Box(
                Modifier
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(Ca.radius.pill))
                    .clickable { onSelect(view) }
                    .padding(horizontal = 14.dp, vertical = if (touchInput) 8.dp else 5.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A test run's results: a summary (counts, a progress bar while it runs, Rerun failed) over the tests grouped by
 * class. A failed test opens to its message; each test and class can be run again on its own, and opened in the
 * editor when [onOpen] is given.
 */
@Composable
internal fun TestResults(
    tests: UiTestRun,
    running: Boolean,
    onRun: (className: String, method: String?) -> Unit,
    onOpen: ((className: String, method: String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val passColor = Ide.colors.run
    val failColor = MaterialTheme.colorScheme.error
    Column(
        modifier.fillMaxWidth()
            .background(Ide.colors.consoleBg, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.md)),
    ) {
        TestSummary(tests, running, passColor, failColor)
        if (tests.noTests) {
            Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                Text(stringResource(Res.string.tests_none_found), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        // Failures first within the list so they are seen without scrolling; run order otherwise.
        val groups = remember(tests) {
            tests.byClass.entries.sortedBy { (cls, cases) -> if (cases.any { it.status == UiTestStatus.Failed } || cls in tests.classFailures) 0 else 1 }
        }
        var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
        // Method groups the user opened or closed, against their default (open while running or failed).
        var toggledGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            for ((cls, cases) in groups) {
                item(key = "c:$cls") {
                    ClassRow(cls, cases, tests.classFailures[cls], running, onRun, onOpen, passColor, failColor)
                }
                // A parameterized or repeated test's runs (`add[1] …`) sit under one row for the test.
                for ((method, runs) in cases.groupBy { it.name.substringBefore('[') }) {
                    val isGroup = runs.size > 1 || runs.single().name != method
                    val testCase: @Composable (UiTestCase, Dp) -> Unit = { case, indent ->
                        val key = "$cls#${case.name}"
                        TestRow(
                            case, expanded = key in expanded, running = running,
                            onToggle = { expanded = if (key in expanded) expanded - key else expanded + key },
                            onRun = { onRun(cls, case.name) },
                            onOpen = onOpen?.let { open -> { open(cls, method) } },
                            passColor = passColor, failColor = failColor, indent = indent,
                        )
                    }
                    if (!isGroup) {
                        item(key = "t:$cls#${runs.single().name}") { testCase(runs.single(), 30.dp) }
                        continue
                    }
                    val groupKey = "$cls#$method"
                    val defaultOpen = runs.any { it.status == UiTestStatus.Failed || it.status == UiTestStatus.Running }
                    val open = defaultOpen != (groupKey in toggledGroups)
                    item(key = "g:$groupKey") {
                        MethodGroupRow(
                            method, runs, open, running,
                            onToggle = { toggledGroups = if (groupKey in toggledGroups) toggledGroups - groupKey else toggledGroups + groupKey },
                            onRun = { onRun(cls, method) },
                            passColor = passColor, failColor = failColor,
                        )
                    }
                    if (open) items(runs, key = { "t:$cls#${it.name}" }) { case -> testCase(case, 52.dp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestSummary(tests: UiTestRun, running: Boolean, passColor: Color, failColor: Color) {
    val done = tests.cases.count { it.status != UiTestStatus.Queued && it.status != UiTestStatus.Running }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val (icon, tint) = when {
                tests.failed > 0 -> CaIcons.close to failColor
                running && !tests.finished -> null to MaterialTheme.colorScheme.primary
                else -> CaIcons.check to passColor
            }
            if (icon == null) CircularProgressIndicator(Modifier.size(14.dp), color = tint, strokeWidth = 2.dp)
            else Icon(icon, null, Modifier.size(16.dp), tint = tint)
            // Wraps on a narrow phone rather than squeezing any one count.
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (tests.failed > 0) SummaryCount(stringResource(Res.string.tests_failed, tests.failed), failColor)
                SummaryCount(stringResource(Res.string.tests_passed, tests.passed), passColor)
                if (tests.skipped > 0) SummaryCount(stringResource(Res.string.tests_skipped, tests.skipped), MaterialTheme.colorScheme.outline)
                if (tests.finished) Text(formatMs(tests.durationMs), style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                else if (tests.cases.isNotEmpty()) Text(stringResource(Res.string.tests_progress, done, tests.cases.size), style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
        }
        if (!tests.finished && tests.cases.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { done.toFloat() / tests.cases.size },
                modifier = Modifier.fillMaxWidth(),
                color = if (tests.failed > 0) failColor else passColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }
    }
}

/** Runs the failed tests again; beside the Tests / Output switch. */
@Composable
internal fun RerunFailedButton(onClick: () -> Unit) {
    val failColor = MaterialTheme.colorScheme.error
    Row(
        Modifier.background(failColor.copy(alpha = 0.12f), RoundedCornerShape(Ca.radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (touchInput) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(CaIcons.refresh, null, Modifier.size(13.dp), tint = failColor)
        Text(stringResource(Res.string.tests_rerun_failed), style = MaterialTheme.typography.labelLarge, color = failColor, maxLines = 1)
    }
}

@Composable
private fun SummaryCount(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
}

@Composable
private fun ClassRow(
    cls: String,
    cases: List<UiTestCase>,
    classFailure: String?,
    running: Boolean,
    onRun: (String, String?) -> Unit,
    onOpen: ((String, String?) -> Unit)?,
    passColor: Color,
    failColor: Color,
) {
    val status = when {
        classFailure != null || cases.any { it.status == UiTestStatus.Failed } -> UiTestStatus.Failed
        cases.any { it.status == UiTestStatus.Running || it.status == UiTestStatus.Queued } -> if (running) UiTestStatus.Running else UiTestStatus.Queued
        cases.all { it.status == UiTestStatus.Skipped } -> UiTestStatus.Skipped
        else -> UiTestStatus.Passed
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onOpen != null) Modifier.clickable { onOpen(cls, null) } else Modifier)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusGlyph(status, passColor, failColor)
            Text(
                cls.substringAfterLast('.'),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                cls.substringBeforeLast('.', ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (!running) IconButtonCa(CaIcons.play, stringResource(Res.string.tests_run_class), { onRun(cls, null) }, boxSize = rowIconBox(), iconSize = 13, tint = Ide.colors.run)
        }
        if (classFailure != null) {
            Text(
                stringResource(Res.string.tests_class_failed, classFailure),
                style = Ide.type.codeSmall, color = failColor,
                modifier = Modifier.padding(start = 34.dp, end = 12.dp, bottom = 4.dp),
            )
        }
    }
}

/** One parameterized or repeated test: how its runs went, opening to list them. */
@Composable
private fun MethodGroupRow(
    method: String,
    runs: List<UiTestCase>,
    open: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    passColor: Color,
    failColor: Color,
) {
    val status = when {
        runs.any { it.status == UiTestStatus.Failed } -> UiTestStatus.Failed
        runs.any { it.status == UiTestStatus.Running } -> UiTestStatus.Running
        runs.any { it.status == UiTestStatus.Queued } -> UiTestStatus.Queued
        runs.all { it.status == UiTestStatus.Skipped } -> UiTestStatus.Skipped
        else -> UiTestStatus.Passed
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 30.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusGlyph(status, passColor, failColor)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                method, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(if (open) CaIcons.chevronUp else CaIcons.chevronDown, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
        }
        val passed = runs.count { it.status == UiTestStatus.Passed }
        Text("$passed/${runs.size}", style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.outline)
        if (!running) IconButtonCa(CaIcons.play, stringResource(Res.string.tests_run_one), onRun, boxSize = rowIconBox(), iconSize = 13, tint = Ide.colors.run)
    }
}

@Composable
private fun TestRow(
    case: UiTestCase,
    expanded: Boolean,
    running: Boolean,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onOpen: (() -> Unit)?,
    passColor: Color,
    failColor: Color,
    indent: Dp = 30.dp,
) {
    val hasDetail = case.message.isNotEmpty()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (onOpen != null) onOpen() else if (hasDetail) onToggle() }
                .padding(start = indent, end = 4.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusGlyph(case.status, passColor, failColor)
            Text(
                case.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (case.status == UiTestStatus.Skipped) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (case.status == UiTestStatus.Passed || case.status == UiTestStatus.Failed) {
                Text(formatMs(case.durationMs), style = Ide.type.codeSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (!running) IconButtonCa(CaIcons.play, stringResource(Res.string.tests_run_one), onRun, boxSize = rowIconBox(), iconSize = 13, tint = Ide.colors.run)
        }
        // A failure's message is the point of the run: shown in full when opened, one line otherwise.
        if (hasDetail) {
            val color = if (case.status == UiTestStatus.Failed) failColor else MaterialTheme.colorScheme.outline
            Box(
                Modifier.fillMaxWidth()
                    .padding(start = indent + 24.dp, end = 12.dp, bottom = 4.dp)
                    .then(if (expanded) Modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(Ca.radius.sm)).padding(8.dp) else Modifier)
                    .clickable(onClick = onToggle),
            ) {
                if (expanded) SelectionContainer { Text(case.message, style = Ide.type.codeSmall, color = color) }
                else Text(shortMessage(case.message), style = Ide.type.codeSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StatusGlyph(status: UiTestStatus, passColor: Color, failColor: Color) {
    val box = Modifier.size(16.dp)
    when (status) {
        UiTestStatus.Running -> Box(box, Alignment.Center) { CircularProgressIndicator(Modifier.size(12.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 1.5.dp) }
        else -> {
            val (icon: ImageVector, tint: Color) = when (status) {
                UiTestStatus.Passed -> CaIcons.check to passColor
                UiTestStatus.Failed -> CaIcons.close to failColor
                UiTestStatus.Skipped -> CaIcons.minus to MaterialTheme.colorScheme.outline
                else -> CaIcons.dot to MaterialTheme.colorScheme.outlineVariant
            }
            Box(box.background(tint.copy(alpha = 0.14f), RoundedCornerShape(50)), Alignment.Center) {
                Icon(icon, null, Modifier.size(11.dp), tint = tint)
            }
        }
    }
}

@Composable
private fun rowIconBox(): Int = if (touchInput) 34 else 26

/** A message's first line with a leading exception's package dropped (`AssertionError: …`), for one line. */
internal fun shortMessage(message: String): String =
    message.lineSequence().first().replace(Regex("^(?:[a-z_][\\w$]*\\.)+([A-Z][\\w$]*)"), "$1")

private fun formatMs(ms: Long): String = when {
    ms < 1000 -> "$ms ms"
    else -> "${ms / 1000}.${(ms % 1000) / 100} s"
}
