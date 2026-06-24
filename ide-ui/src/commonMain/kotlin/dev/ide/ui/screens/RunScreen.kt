package dev.ide.ui.screens

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
import androidx.compose.ui.graphics.Color
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
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.RunConsoleUi
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.RunStatus
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca

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
    val console by backend.runConsole.collectAsState()
    val build by backend.buildState.collectAsState()
    val rc = console
    Column(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        RunTopBar(
            console = rc,
            buildFailed = build.status == RunStatus.Failed,
            onBack = onBack,
            onStop = { backend.stopBuild() },
            onRerun = { backend.runBuild() },
        )
        if (rc == null) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No active run.", color = Ca.colors.textTertiary, style = Ca.type.footnote)
            }
            return@Column
        }
        BuildPhaseStrip(rc, build, onOpenDiagnostic)
        Transcript(rc, Modifier.weight(1f).padding(horizontal = 14.dp))
        if (rc.acceptsInput) {
            InputBar(
                onSend = { backend.sendRunInput(it) },
                onEof = { backend.closeRunInput() },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        } else {
            Spacer(Modifier.size(12.dp))
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
        IconButtonCa(CaIcons.chevronLeft, "Back", onBack, boxSize = iconBox)
        Icon(CaIcons.terminal, null, Modifier.size(18.dp), tint = Ca.colors.textSecondary)
        Column(Modifier.weight(1f)) {
            Text(
                console?.moduleName?.ifEmpty { "Run" } ?: "Run",
                style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            console?.mainClass?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = Ca.type.caption, color = Ca.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (console != null) RunStatusPill(console, buildFailed)
        if (console != null && console.transcript.isNotEmpty()) {
            IconButtonCa(
                CaIcons.copy, "Copy output",
                onClick = { clipboard.setText(AnnotatedString(console.transcript.joinToString("") { it.text })) },
                boxSize = iconBox, iconSize = 16, tint = Ca.colors.textSecondary,
            )
        }
        if (running) IconButtonCa(CaIcons.stop, "Stop", onStop, boxSize = iconBox, iconSize = 16, tint = Ca.colors.error)
        else IconButtonCa(CaIcons.play, "Run again", onRerun, boxSize = iconBox, iconSize = 16, tint = Ca.colors.run)
    }
}

@Composable
private fun RunStatusPill(console: RunConsoleUi, buildFailed: Boolean) {
    val (text, color) = when (console.phase) {
        RunPhase.Building -> "Building…" to Ca.colors.accent
        RunPhase.Running -> "Running" to Ca.colors.run
        RunPhase.Finished -> when (val c = console.exitCode) {
            0 -> "Exit 0" to Ca.colors.run
            null -> if (buildFailed) "Failed" to Ca.colors.error else "Stopped" to Ca.colors.textSecondary
            else -> "Exit $c" to Ca.colors.error
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
                CircularProgressIndicator(Modifier.size(14.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
                val done = build.steps.count { it.status.name == "Done" || it.status.name == "UpToDate" }
                Text(
                    "Building ${console.moduleName}…" + if (build.steps.isNotEmpty()) "  $done/${build.steps.size}" else "",
                    color = Ca.colors.textSecondary, style = Ca.type.footnote,
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
        val color = if (d.severity == UiSeverity.Error) Ca.colors.error else if (d.severity == UiSeverity.Warning) Ca.colors.warning else Ca.colors.accent
        Icon(if (d.severity == UiSeverity.Error) CaIcons.error else if (d.severity == UiSeverity.Warning) CaIcons.warning else CaIcons.info, null, Modifier.size(14.dp).padding(top = 1.dp), tint = color)
        Text(d.message, color = Ca.colors.textSecondary, style = Ca.type.footnote, modifier = Modifier.weight(1f))
        if (d.file != null) Text(d.file!!.substringAfterLast('/').substringAfterLast('\\') + (if (d.line > 0) ":${d.line}" else ""), color = Ca.colors.textTertiary, style = Ca.type.codeSmall)
    }
}

@Composable
private fun Transcript(console: RunConsoleUi, modifier: Modifier) {
    val scroll = rememberScrollState()
    val total = console.transcript.sumOf { it.text.length }
    // Follow the tail as output (and echoed input) arrives.
    LaunchedEffect(total) { scroll.scrollTo(scroll.maxValue) }
    val text = remember(total, console.transcript.size) {
        buildAnnotatedString {
            for (chunk in console.transcript) {
                withStyle(SpanStyle(color = chunkColor(chunk.kind))) { append(chunk.text) }
            }
        }
    }
    Box(
        modifier.fillMaxWidth()
            .background(Ca.colors.consoleBg, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md))
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        if (console.transcript.isEmpty()) {
            Text(
                if (console.phase == RunPhase.Building) "Building…" else "(no output)",
                color = Ca.colors.textTertiary, style = Ca.type.codeSmall,
            )
        } else {
            SelectionContainer { Text(text, style = Ca.type.codeSmall) }
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
    val rc by backend.runConsole.collectAsState()
    val console = rc
    if (console == null || console.phase == RunPhase.Finished) return
    Row(
        modifier
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.pill))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Ca.colors.accent, strokeWidth = 2.dp)
        Text(
            (if (console.phase == RunPhase.Building) "Building " else "Running ") + console.moduleName,
            color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium,
        )
        Icon(CaIcons.chevronRight, null, Modifier.size(14.dp), tint = Ca.colors.textTertiary)
    }
}

private fun chunkColor(kind: ConsoleChunkKind): Color = when (kind) {
    ConsoleChunkKind.OUTPUT -> Color(0xFFE6E6E6)
    ConsoleChunkKind.INPUT -> Color(0xFF7FB4FF)
    ConsoleChunkKind.SYSTEM -> Color(0xFF8A8A8A)
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
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (field.text.isEmpty()) Text("Type input, press Enter…", color = Ca.colors.textTertiary, style = Ca.type.code)
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = Ca.type.code.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                        submit(); true
                    } else false
                },
            )
        }
        IconButtonCa(CaIcons.arrowRight, "Send", { submit() }, boxSize = 38, iconSize = 18, tint = Ca.colors.accent)
        IconButtonCa(CaIcons.close, "End input (EOF)", onEof, boxSize = 38, iconSize = 16, tint = Ca.colors.textTertiary)
    }
}
