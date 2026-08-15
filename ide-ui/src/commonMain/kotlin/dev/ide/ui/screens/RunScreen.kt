package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.clipForClipboard
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.copy
import dev.ide.ui.generated.resources.run
import dev.ide.ui.generated.resources.run_back
import dev.ide.ui.generated.resources.run_building
import dev.ide.ui.generated.resources.run_building_module
import dev.ide.ui.generated.resources.run_end_input_eof
import dev.ide.ui.generated.resources.run_exit_code
import dev.ide.ui.generated.resources.run_failed
import dev.ide.ui.generated.resources.run_no_active_run
import dev.ide.ui.generated.resources.run_no_output
import dev.ide.ui.generated.resources.run_run_again
import dev.ide.ui.generated.resources.run_running
import dev.ide.ui.generated.resources.run_running_module
import dev.ide.ui.generated.resources.run_send
import dev.ide.ui.generated.resources.run_stopped
import dev.ide.ui.generated.resources.run_type_input
import dev.ide.ui.generated.resources.stop
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The full-screen Run terminal: a console run's program output + an input bar so the user can type when
 * the program reads stdin, plus the build phase (compile/dex) and any compile errors. Talks only to the
 * [IdeBackend] port — program stdio flows through [IdeBackend.runConsole], input back through
 * [IdeBackend.sendRunInput]. The run sandbox's permission prompts are surfaced by the app-wide
 * `PermissionDialog`, which overlays this screen.
 */
@Composable
fun RunScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    onOpenDiagnostic: (BuildDiagnosticUi) -> Unit = {},
) {
    val console by backend.build.runConsole.collectAsState()
    val build by backend.build.buildState.collectAsState()
    val rc = console
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        RunTopBar(
            console = rc,
            buildFailed = build.status == RunStatus.Failed,
            onBack = onBack,
            onStop = { backend.build.stopBuild() },
            onRerun = { backend.build.runBuild() },
        )
        if (rc == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(Res.string.run_no_active_run), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        BuildPhaseStrip(rc, build, onOpenDiagnostic)
        Transcript(rc, Modifier.weight(1f).padding(horizontal = 14.dp))
        when {
            rc.acceptsInput -> InputBar(
                onSend = { backend.build.sendRunInput(it) },
                onEof = { backend.build.closeRunInput() },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
            // The program has finished: idle attention, no workflow to interrupt — a native ad reads as calm here.
            rc.phase == RunPhase.Finished -> AdSlot(AdPlacement.RUN_RESULT, Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            else -> Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun RunTopBar(console: RunConsoleUi?, buildFailed: Boolean, onBack: () -> Unit, onStop: () -> Unit, onRerun: () -> Unit) {
    val iconBox = if (isMobilePlatform) 42 else 34
    val running = console?.phase == RunPhase.Running || console?.phase == RunPhase.Building
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButtonCa(CaIcons.chevronLeft, stringResource(Res.string.run_back), onBack, boxSize = iconBox)
        Icon(CaIcons.terminal, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val runLabel = stringResource(Res.string.run)
        Column(Modifier.weight(1f)) {
            Text(
                console?.moduleName?.ifEmpty { runLabel } ?: runLabel,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            console?.mainClass?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (console != null) RunStatusPill(console, buildFailed)
        if (console != null && console.transcript.isNotEmpty()) {
            IconButtonCa(
                CaIcons.copy, stringResource(Res.string.copy),
                onClick = { clipboard.setText(AnnotatedString(clipForClipboard(console.transcript.joinToString("") { it.text }))) },
                boxSize = iconBox, iconSize = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (running) IconButtonCa(CaIcons.stop, stringResource(Res.string.stop), onStop, boxSize = iconBox, iconSize = 16, tint = MaterialTheme.colorScheme.error)
        else IconButtonCa(CaIcons.play, stringResource(Res.string.run_run_again), onRerun, boxSize = iconBox, iconSize = 16, tint = Ide.colors.run)
    }
}

@Composable
private fun RunStatusPill(console: RunConsoleUi, buildFailed: Boolean) {
    val (text, color) = when (console.phase) {
        RunPhase.Building -> stringResource(Res.string.run_building) to MaterialTheme.colorScheme.primary
        RunPhase.Running -> stringResource(Res.string.run_running) to Ide.colors.run
        RunPhase.Finished -> when (val c = console.exitCode) {
            0 -> stringResource(Res.string.run_exit_code, 0) to Ide.colors.run
            null -> if (buildFailed) stringResource(Res.string.run_failed) to MaterialTheme.colorScheme.error else stringResource(Res.string.run_stopped) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> stringResource(Res.string.run_exit_code, c) to MaterialTheme.colorScheme.error
        }
    }
    Chip(text, fill = color.copy(alpha = 0.16f), textColor = color)
}

/**
 * The compile/dex phase: a one-line status while building, plus any compiler diagnostics so a build that
 * fails before the program starts still shows its errors. Renders nothing once a program is running and
 * there are no problems to show.
 */
@Composable
private fun BuildPhaseStrip(console: RunConsoleUi, build: BuildState, onOpen: (BuildDiagnosticUi) -> Unit) {
    val building = console.phase == RunPhase.Building
    val errors = build.diagnostics.count { it.severity == UiSeverity.Error }
    if (!building && errors == 0) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (building) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                val done = build.steps.count { it.status.name == "Done" || it.status.name == "UpToDate" }
                Text(
                    stringResource(Res.string.run_building_module, console.moduleName) + if (build.steps.isNotEmpty()) "  $done/${build.steps.size}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (build.diagnostics.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (d in build.diagnostics.sortedBy { if (it.severity == UiSeverity.Error) 0 else 1 }) ProblemRow(d, onOpen)
            }
        }
    }
}

@Composable
private fun ProblemRow(d: BuildDiagnosticUi, onOpen: (BuildDiagnosticUi) -> Unit) {
    val clickable = d.file != null
    Row(
        Modifier.fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onOpen(d) } else Modifier)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = if (d.severity == UiSeverity.Error) MaterialTheme.colorScheme.error else if (d.severity == UiSeverity.Warning) Ide.colors.warning else MaterialTheme.colorScheme.primary
        Icon(if (d.severity == UiSeverity.Error) CaIcons.error else if (d.severity == UiSeverity.Warning) CaIcons.warning else CaIcons.info, null, Modifier.size(14.dp).padding(top = 1.dp), tint = color)
        Text(d.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (d.file != null) Text(d.file!!.substringAfterLast('/').substringAfterLast('\\') + (if (d.line > 0) ":${d.line}" else ""), color = MaterialTheme.colorScheme.outline, style = Ide.type.codeSmall)
    }
}

@Composable
private fun Transcript(console: RunConsoleUi, modifier: Modifier) {
    val scroll = rememberScrollState()
    val total = console.transcript.sumOf { it.text.length }
    // Follow the tail as output (and echoed input) arrives.
    LaunchedEffect(total) { scroll.scrollTo(scroll.maxValue) }
    // Theme-aware transcript colors so they read on BOTH the dark and the light [consoleBg]. (OUTPUT used to be
    // a hardcoded near-white — invisible on the light-theme console.) Keyed into the remember so the transcript
    // re-styles on a theme switch.
    val outputColor = MaterialTheme.colorScheme.onSurface
    val inputColor = MaterialTheme.colorScheme.primary
    val systemColor = MaterialTheme.colorScheme.outline
    val text = remember(total, console.transcript.size, outputColor, inputColor, systemColor) {
        buildAnnotatedString {
            for (chunk in console.transcript) {
                val color = when (chunk.kind) {
                    ConsoleChunkKind.OUTPUT -> outputColor
                    ConsoleChunkKind.INPUT -> inputColor
                    ConsoleChunkKind.SYSTEM -> systemColor
                }
                withStyle(SpanStyle(color = color)) { append(chunk.text) }
            }
        }
    }
    Box(
        modifier.fillMaxWidth()
            .background(Ide.colors.consoleBg, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.md))
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        if (console.transcript.isEmpty()) {
            Text(
                if (console.phase == RunPhase.Building) stringResource(Res.string.run_building) else stringResource(Res.string.run_no_output),
                color = MaterialTheme.colorScheme.outline, style = Ide.type.codeSmall,
            )
        } else {
            SelectionContainer { Text(text, style = Ide.type.codeSmall) }
        }
    }
}

/**
 * A compact floating indicator the editor shows while a console run is active but the Run terminal isn't on
 * screen (the user navigated back during a build/run). Tap to return to it. Renders nothing when no run is in
 * progress, so a finished run leaves no clutter.
 */
@Composable
fun RunningIndicator(backend: IdeBackend, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rc by backend.build.runConsole.collectAsState()
    val console = rc
    if (console == null || console.phase == RunPhase.Finished) return
    Row(
        modifier
            .background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.pill))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Text(
            if (console.phase == RunPhase.Building) stringResource(Res.string.run_building_module, console.moduleName)
            else stringResource(Res.string.run_running_module, console.moduleName),
            color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
        )
        Icon(CaIcons.chevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** Bottom input row: type a line and Enter/Send feeds it to the program's stdin; EOF closes the stream. */
@Composable
private fun InputBar(onSend: (String) -> Unit, onEof: () -> Unit, modifier: Modifier = Modifier) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        onSend(field.text)
        field = TextFieldValue("")
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (field.text.isEmpty()) Text(stringResource(Res.string.run_type_input), color = MaterialTheme.colorScheme.outline, style = Ide.type.code)
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = Ide.type.code.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                        submit(); true
                    } else false
                },
            )
        }
        IconButtonCa(CaIcons.arrowRight, stringResource(Res.string.run_send), { submit() }, boxSize = 38, iconSize = 18, tint = MaterialTheme.colorScheme.primary)
        IconButtonCa(CaIcons.close, stringResource(Res.string.run_end_input_eof), onEof, boxSize = 38, iconSize = 16, tint = MaterialTheme.colorScheme.outline)
    }
}
