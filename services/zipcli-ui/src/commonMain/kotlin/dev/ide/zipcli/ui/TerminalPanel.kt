package dev.ide.zipcli.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ide.ui.theme.Ide

/**
 * The ZipCLI terminal: scrollback on top, a confirmation strip when a destructive step is waiting, and the
 * input line at the bottom. Enter runs the line, Up / Down walk the history (hardware keys, or the two arrow
 * buttons on a touch screen), and the scrollback is selectable so output can be copied.
 */
@Composable
fun TerminalPanel(session: TerminalSession, modifier: Modifier = Modifier) {
    val lines by session.lines.collectAsState()
    val cwd by session.cwd.collectAsState()
    val busy by session.busy.collectAsState()
    val prompt by session.prompt.collectAsState()

    val codeStyle = Ide.type.codeSmall
    val textColor = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val accent = MaterialTheme.colorScheme.primary

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    var input by remember { mutableStateOf(TextFieldValue("")) }
    fun run() {
        val text = input.text
        if (text.isBlank() || busy) return
        input = TextFieldValue("")
        session.submit(text)
    }
    fun recall(text: String?) {
        if (text != null) input = TextFieldValue(text, TextRange(text.length))
    }

    Column(modifier.fillMaxSize().background(Ide.colors.consoleBg)) {
        // Header: where commands run, and the two actions that apply to the scrollback / the running command.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(cwd, style = codeStyle, color = dim, modifier = Modifier.weight(1f), maxLines = 1)
            if (busy) TermAction("Stop", errorColor) { session.stop() }
            TermAction("Clear", dim) { session.clear() }
        }

        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(lines) { line ->
                    Text(
                        line.text,
                        style = codeStyle,
                        color = when (line.kind) {
                            TerminalLineKind.INPUT -> accent
                            TerminalLineKind.OUTPUT -> textColor
                            TerminalLineKind.ERROR -> errorColor
                            TerminalLineKind.INFO -> dim
                        },
                    )
                }
            }
        }

        val pending = prompt
        if (pending != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(pending.text, style = codeStyle, color = textColor, modifier = Modifier.weight(1f))
                TermAction("Yes", accent) { session.answer(true) }
                TermAction("No", dim) { session.answer(false) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$", style = codeStyle, color = accent)
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = codeStyle.copy(color = textColor),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { run() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.Enter, Key.NumPadEnter -> { run(); true }
                                Key.DirectionUp -> { recall(session.previous()); true }
                                Key.DirectionDown -> { recall(session.next()); true }
                                else -> false
                            }
                        },
                )
                if (input.text.isEmpty() && !busy) {
                    Text("command, e.g. find TODO src", style = codeStyle, color = dim.copy(alpha = 0.6f))
                }
            }
            TermAction("↑", dim) { recall(session.previous()) }
            TermAction("↓", dim) { recall(session.next()) }
            TermAction("Run", accent) { run() }
        }
    }
}

/** A small text button: touch-sized without pulling in a button style that would fight the console's look. */
@Composable
private fun TermAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = Ide.type.codeSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
