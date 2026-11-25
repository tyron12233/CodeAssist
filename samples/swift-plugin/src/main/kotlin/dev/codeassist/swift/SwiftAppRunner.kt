package dev.codeassist.swift

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * A SwiftUI app, run full screen over the IDE: what Run of an app target (`@main struct MyApp: App`) opens. The app
 * runs as previews do (the JIT, CodeAssist's SwiftUI engine, the same painter), drawn on the iPhone canvas at the
 * largest size that fits, live: taps, text fields, navigation, sheets. Closing it stops the app.
 *
 * An overlay rather than a screen, because the request comes from a Run, which may execute in the IDE's build
 * process: it watches the request file ([SwiftAppRequests]) and shows itself when a new request arrives.
 */
@Composable
internal fun SwiftAppRunner() {
    val tools = SwiftPlugin.tools ?: return
    val home = tools.toolchain.home
    var request by remember { mutableStateOf<Pair<Path, String>?>(null) }
    // A request from before this IDE session (an app left open last time) is not reopened.
    var seen by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        seen = withContext(Dispatchers.IO) { SwiftAppRequests.read(home)?.second }
        while (true) {
            delay(400)
            val latest = withContext(Dispatchers.IO) { SwiftAppRequests.read(home) }
            if (latest != null && latest.second != seen) {
                seen = latest.second
                request = latest
            }
        }
    }
    val open = request ?: return
    AppScreen(open.first, open.second, tools) { request = null }
}

@Composable
private fun AppScreen(target: Path, stamp: String, tools: SwiftPlugin.Tools, onClose: () -> Unit) {
    val session = remember(target, stamp) { SwiftPreviewSession(target, tools.toolchain, tools.text, tools.log, app = true) }
    DisposableEffect(session) { onDispose { session.close() } }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(session, dark) { session.update(stamp, dark) }
    val state by session.state.collectAsState()
    var editing by remember { mutableStateOf<SwiftPreviewFrame.Field?>(null) }

    // Opaque and swallowing touches: the IDE underneath is not reachable while an app runs over it.
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }) {
        // Inside the system bars: the runner covers the whole window, edge to edge.
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(target.fileName.toString(), color = Color.White, style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "Close", color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.fillMaxWidth().padding(top = 4.dp))
            Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                val frame = state.frames.firstOrNull()
                when {
                    frame != null -> Device(frame, tools.painter, stale = state.busy) { op ->
                        when (op) {
                            is SwiftPreviewFrame.Hit -> session.tap(op.id)
                            is SwiftPreviewFrame.Field -> editing = op
                            else -> Unit
                        }
                    }
                    state.problems.isNotEmpty() -> Message("The app did not compile:\n\n" + state.problems.joinToString("\n\n"))
                    state.message != null -> Message(state.message!!)
                    else -> Message("Starting ${target.fileName}…")
                }
            }
        }
    }

    editing?.let { field ->
        var value by remember(field) { mutableStateOf(field.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = { TextButton(onClick = { session.set(field.id, value); editing = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = Color(0xFFDDDDDD), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
}
