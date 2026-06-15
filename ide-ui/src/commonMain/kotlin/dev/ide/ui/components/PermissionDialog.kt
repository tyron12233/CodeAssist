package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import dev.ide.ui.theme.Ca

/**
 * The run sandbox's permission prompt: while a program the IDE runs hits a guarded operation (network /
 * file / reflection / process), it is blocked until the user answers here. Observes
 * [IdeBackend.permissionRequest] and routes the choice back via [IdeBackend.answerPermission]. Dismissing
 * (scrim tap) denies — the safe default. Hosted app-wide so it overlays the running program.
 */
@Composable
fun PermissionDialog(backend: IdeBackend) {
    val request by backend.permissionRequest.collectAsState()
    // Retain the last request so the exit animation doesn't flash empty as it clears.
    var shown by remember { mutableStateOf<UiPermissionRequest?>(null) }
    if (request != null) shown = request

    fun answer(decision: UiPermissionDecision) { shown?.let { backend.answerPermission(it.id, decision) } }

    CenteredDialog(visible = request != null, onDismiss = { answer(UiPermissionDecision.DENY) }) {
        shown?.let { req -> PermissionCard(req, ::answer) }
    }
}

@Composable
private fun PermissionCard(req: UiPermissionRequest, answer: (UiPermissionDecision) -> Unit) {
    val (title, verb) = labelFor(req.category)
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
            .padding(20.dp),
    ) {
        Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "The running program wants to $verb:",
            color = Ca.colors.textSecondary, style = Ca.type.footnote,
        )
        Spacer(Modifier.height(2.dp))
        Text(req.detail, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))

        PermButton("Allow once", PermKind.Neutral) { answer(UiPermissionDecision.ALLOW_ONCE) }
        Spacer(Modifier.height(8.dp))
        PermButton("Allow for this run", PermKind.Accent) { answer(UiPermissionDecision.ALLOW_RUN) }
        Spacer(Modifier.height(8.dp))
        PermButton("Always allow in this project", PermKind.Neutral) { answer(UiPermissionDecision.ALLOW_ALWAYS) }
        Spacer(Modifier.height(8.dp))
        PermButton("Deny", PermKind.Danger) { answer(UiPermissionDecision.DENY) }
    }
}

private enum class PermKind { Neutral, Accent, Danger }

@Composable
private fun PermButton(label: String, kind: PermKind, onClick: () -> Unit) {
    val fill = when (kind) {
        PermKind.Accent -> Ca.colors.accent
        PermKind.Danger -> Ca.colors.error.copy(alpha = 0.16f)
        PermKind.Neutral -> Ca.colors.surface3
    }
    val fg = when (kind) {
        PermKind.Accent -> Ca.colors.textOnAccent
        PermKind.Danger -> Ca.colors.error
        PermKind.Neutral -> Ca.colors.textPrimary
    }
    Text(
        label,
        color = fg,
        style = Ca.type.footnote,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(Ca.radius.control))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

/** Title + verb for a guard-category id (matches GuardCategory.name().lowercase()). */
private fun labelFor(category: String): Pair<String, String> = when (category) {
    "network" -> "Allow network access?" to "connect to the network"
    "file_read" -> "Allow reading a file?" to "read the file"
    "file_write" -> "Allow writing a file?" to "write the file"
    "reflection" -> "Allow reflection?" to "use reflection on"
    "exec" -> "Allow running a process?" to "run the process"
    else -> "Allow this action?" to "perform"
}
