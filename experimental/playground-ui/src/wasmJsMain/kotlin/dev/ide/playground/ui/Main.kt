// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.playground.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.ide.ui.editor.CodeEditor
import dev.ide.ui.editor.CodeLanguage
import dev.ide.ui.editor.core.EditorSession
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

@Composable
fun PlaygroundApp() {
    // The IDE sets its code in the bundled JetBrains Mono. The default, FontFamily.Monospace, names a system font,
    // and a browser canvas has none to resolve it to: it falls back to a proportional face.
    CodeAssistTheme(dark = true, codeFont = rememberJetBrainsMono()) {
        var status by remember { mutableStateOf("starting the engine...") }
        var output by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        var timing by remember { mutableStateOf("") }
        var engineReady by remember { mutableStateOf(false) }
        val engine = remember { EngineClient(onStatus = { status = it }, onReady = { engineReady = true }) }
        val backend = remember { PlaygroundBackend(engine) }
        val session = remember { EditorSession(SAMPLE, CodeLanguage.Kotlin) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { engine.start() }

        // The analysis daemon the full app runs per open file: after the reparse delay it asks the backend for
        // diagnostics (type-aware colours and inlay hints, too; folds and decorations this backend leaves empty) and hands them to the session to
        // draw. Restarted on every edit, and once the engine is up, so the first diagnostics do not wait for a key.
        val daemon = remember { EditorEngineDaemon(scope, backend, PATH) }
        daemon.onDiagnostics = { session.applyAnalysis(it) }
        daemon.onSemanticTokens = { session.applySemanticTokens(it) }
        daemon.onInlayHints = { session.applyInlayHints(it) }
        DisposableEffect(daemon) { onDispose { daemon.close() } }
        LaunchedEffect(session.textRevision, engineReady) {
            if (engineReady) daemon.restart(session.doc.text.toString())
        }

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        status = "running..."
                        val r = engine.run(session.doc.text.toString())
                        failed = r.error.isNotEmpty()
                        output = if (failed) r.stdout + (if (r.stdout.isNotEmpty()) "\n" else "") + r.error else r.stdout
                        timing = r.timing.split('/').takeIf { it.size == 2 }?.let { "analysis ${it[0]} ms, run ${it[1]} ms" } ?: ""
                        status = "ready"
                    }
                }) { Text("Run") }
                Text("  $status  $timing", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            }
            CodeEditor(path = PATH, session = session, backend = backend, modifier = Modifier.weight(1f).fillMaxWidth())
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
