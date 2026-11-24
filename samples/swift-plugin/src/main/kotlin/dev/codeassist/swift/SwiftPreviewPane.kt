package dev.codeassist.swift

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.plugin.ui.EditorPreviewContext
import kotlinx.coroutines.delay
import java.nio.file.Paths

/**
 * The editor's SwiftUI preview: the file's previews rendered by CodeAssist's SwiftUI engine on an iPhone-sized canvas,
 * live as the file is edited, and interactive (taps run the views' actions, text fields take input).
 */
@Composable
internal fun SwiftPreviewPane(ctx: EditorPreviewContext) {
    val tools = SwiftPlugin.tools
    if (tools == null) {
        Notice("The Swift plugin is not running.")
        SideEffect { ctx.reportProblems(emptyList()) }
        return
    }
    val session = remember(ctx.path) { SwiftPreviewSessions.acquire(Paths.get(ctx.path), tools) }
    DisposableEffect(session) { onDispose { SwiftPreviewSessions.release(session) } }
    // Typing restarts the compile; wait for a pause first.
    LaunchedEffect(session, ctx.text, ctx.dark) {
        delay(350)
        session.update(ctx.text, ctx.dark)
    }
    val state by session.state.collectAsState()
    SideEffect { ctx.reportProblems(state.problems) }

    var selected by remember(ctx.path) { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<SwiftPreviewFrame.Field?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.fillMaxWidth().padding(top = 4.dp))
        val frames = state.frames
        if (frames.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                frames.forEachIndexed { i, f ->
                    val on = i == selected.coerceAtMost(frames.lastIndex)
                    Text(
                        f.name.ifEmpty { "Preview ${i + 1}" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { selected = i }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val frame = frames.getOrNull(selected.coerceAtMost(frames.lastIndex))
            when {
                frame != null -> Device(frame, tools.painter, stale = state.busy) { op ->
                    when (op) {
                        is SwiftPreviewFrame.Hit -> session.tap(op.id)
                        is SwiftPreviewFrame.Field -> editing = op
                        else -> Unit
                    }
                }
                state.message != null -> Notice(state.message!!)
                state.problems.isNotEmpty() -> Notice("The preview did not compile. The problems are listed above.")
                else -> Notice("Building the preview…")
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

/**
 * A rendered frame at the largest scale that fits, on the device's screen shape (iPhone 17 Pro's corner radius, about
 * 55 points), or bare for a size-that-fits preview.
 */
@Composable
private fun Device(frame: SwiftPreviewFrame, painter: SwiftPreviewPainter, stale: Boolean, onHit: (SwiftPreviewFrame.Op) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val availW = with(density) { maxWidth.toPx() }
        val availH = with(density) { maxHeight.toPx() }
        val scale = minOf(availW / frame.width, availH / frame.height)
        val w = with(density) { (frame.width * scale).toDp() }
        val h = with(density) { (frame.height * scale).toDp() }
        val isDevice = frame.width == 402f && frame.height == 874f
        val corner = with(density) { (if (isDevice) 55f * scale else 0f).toDp() }
        Canvas(
            Modifier.size(w, h)
                .shadow(if (isDevice) 8.dp else 0.dp, RoundedCornerShape(corner))
                .clip(RoundedCornerShape(corner))
                .alpha(if (stale) 0.7f else 1f)
                .pointerInput(frame) {
                    detectTapGestures { offset -> frame.hitAt(offset.x / scale, offset.y / scale)?.let(onHit) }
                },
        ) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(scale, scale)
                painter.paint(native, frame)
                native.restore()
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
