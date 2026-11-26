// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.editor.core.EditorSession
import dev.ide.ui.editor.folding.FoldRegion
import dev.ide.ui.editor.engine.EditorEngineDaemon
import dev.ide.ui.theme.CodeAssistTheme
import dev.ide.ui.theme.rememberJetBrainsMono
import kotlinx.coroutines.launch

private val SAMPLE = """
package demo

data class Point(val x: Int, val y: Int) {
    fun dist2() = x * x + y * y
}

fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

fun main() {
    val points = listOf(Point(1, 2), Point(3, 4), Point(0, 5))
    println("points=${'$'}points")
    println("farthest=${'$'}{points.maxByOrNull { it.dist2() }}")
    println("fib(15)=${'$'}{fib(15)}")
    println("hello from the IDE's own editor, running in your browser")
}
""".trimStart()

private const val PATH = "/project/Main.kt"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("app") { PlaygroundApp() }
}

/**
 * A problem the interpreter reported with a source location (`... (at Main.kt:12:5)`), as an error on that name in
 * the editor, so a run that fails points at the code instead of only printing in the output pane.
 */
private fun locationDiagnostic(error: String, session: EditorSession): UiDiagnostic? {
    val m = Regex("Main\\.kt:(\\d+):(\\d+)").find(error) ?: return null
    val line = m.groupValues[1].toInt() - 1
    val col = m.groupValues[2].toInt() - 1
    val doc = session.doc
    if (line !in 0 until doc.lineCount) return null
    val start = doc.lineStart(line) + col
    val text = doc.text.toString()
    if (start !in 0 until text.length) return null
    var end = start
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
    if (end == start) end = start + 1
    return UiDiagnostic(
        severity = UiSeverity.Error, line = line + 1, col = col + 1,
        message = error.substringBefore(" (at Main.kt").lineSequence().first(), startOffset = start, endOffset = end,
    )
}

@Composable
fun PlaygroundApp() {
    // The IDE sets its code in the bundled JetBrains Mono. The default, FontFamily.Monospace, names a system font,
    // and a browser canvas has none to resolve it to: it falls back to a proportional face.
    CodeAssistTheme(dark = true, codeFont = rememberJetBrainsMono()) {
        var status by remember { mutableStateOf("starting the engine...") }
        var output by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        var timing by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var engineReady by remember { mutableStateOf(false) }
        var formatEpoch by remember { mutableIntStateOf(0) }
        var importsEpoch by remember { mutableIntStateOf(0) }
        val runSeq = remember { intArrayOf(0) }
        val engine = remember {
            EngineClient(onStatus = { status = it }, onReady = { engineReady = true }, onLost = { engineReady = false })
        }
        val backend = remember { PlaygroundBackend(engine) }
        val session = remember { EditorSession(SAMPLE, CodeLanguage.Kotlin) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { engine.start() }

        // The analysis daemon the full app runs per open file: after the reparse delay it asks the backend for
        // diagnostics, type-aware colours, inlay hints and folds (decorations this backend leaves empty) and hands
        // them to the session to draw. Restarted on every edit, and once the engine is up, so the first results do
        // not wait for a key.
        val daemon = remember { EditorEngineDaemon(scope, backend, PATH) }
        daemon.onDiagnostics = { session.applyAnalysis(it) }
        daemon.onSemanticTokens = { session.applySemanticTokens(it) }
        daemon.onInlayHints = { session.applyInlayHints(it) }
        daemon.onCodeFolds = { folds ->
            session.applyCodeFolds(folds.map { FoldRegion(it.startOffset, it.endOffset, it.placeholder, it.kind, it.collapsedByDefault) })
        }
        DisposableEffect(daemon) { onDispose { daemon.close() } }
        LaunchedEffect(session.textRevision, engineReady) {
            if (engineReady) daemon.restart(session.doc.text.toString())
        }

        fun run() {
            val mine = ++runSeq[0]
            running = true
            failed = false
            output = ""
            timing = ""
            status = "running..."
            scope.launch {
                val r = engine.run(session.doc.text.toString())
                if (runSeq[0] != mine) return@launch // stopped (or superseded) while it ran
                running = false
                failed = r.error.isNotEmpty()
                output = if (failed) r.stdout + (if (r.stdout.isNotEmpty()) "\n" else "") + r.error else r.stdout
                timing = r.timing.split('/').takeIf { it.size == 2 }?.let { "analysis ${it[0]} ms, run ${it[1]} ms" } ?: ""
                status = "ready"
                if (failed) locationDiagnostic(r.error, session)?.let { d ->
                    // The analysis usually flagged the same name already (an unresolved call); do not stack a second one on it.
                    val covered = session.diagnostics.any { it.severity == UiSeverity.Error && it.startOffset < d.endOffset && it.endOffset > d.startOffset }
                    if (!covered) session.applyAnalysis(session.diagnostics + d)
                }
            }
        }

        fun stop() {
            runSeq[0]++ // the run that is waiting is no longer wanted
            running = false
            failed = false
            output = "Stopped."
            timing = ""
            engine.stop()
        }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) Button(onClick = ::stop) { Text("Stop") }
                else Button(onClick = ::run, enabled = engineReady) { Text("Run") }
                OutlinedButton(onClick = { formatEpoch++ }, enabled = engineReady) { Text("Format") }
                OutlinedButton(onClick = { importsEpoch++ }, enabled = engineReady) { Text("Optimize imports") }
                Text(
                    "$status  $timing",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            CodeEditor(
                path = PATH, session = session, backend = backend,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                formatEpoch = formatEpoch,
                optimizeImportsEpoch = importsEpoch,
                // Go to declaration: the engine only answers for a target in this one file.
                onNavigate = { _, offset -> session.setCaret(offset) },
            )
            SelectionContainer {
                Text(
                    output,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                )
            }
        }
    }
}
