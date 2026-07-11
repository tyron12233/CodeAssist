package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.platform.NotificationPermissionStatus
import dev.ide.ui.platform.rememberNotificationPermissionController
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.allow
import dev.ide.ui.generated.resources.build_notif_inprocess_message
import dev.ide.ui.generated.resources.build_notif_inprocess_title
import dev.ide.ui.generated.resources.build_notif_message
import dev.ide.ui.generated.resources.build_notif_not_now
import dev.ide.ui.generated.resources.build_notif_title
import dev.ide.ui.generated.resources.got_it
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The first-build notification-permission gate. The very first time the user starts a build (mobile only),
 * [IdeUiState.requestRun] defers the run to here rather than prompting at app launch: builds and the running
 * program can run in a separate, isolated process, but that process shows an ongoing progress notification, so
 * we ask for the notification permission when it first becomes relevant.
 *
 * Flow: if the permission is already granted (or not applicable), the run proceeds silently. Otherwise a
 * rationale is shown; "Allow" fires the OS request. On a grant the run proceeds (isolated builds start the next
 * time the project is opened). On a decline — the system prompt or "Not now" — an explanation is shown that
 * builds will run inside the app instead, and then the run proceeds in-process. Every path ends in
 * [IdeUiState.resolveNotifGate], which remembers the prompt is done so later builds never gate again.
 *
 * Hosted app-wide (next to `RunConflictDialog`) so it overlays whatever screen the run was started from.
 */
@Composable
fun BuildNotificationGate(state: IdeUiState) {
    val pending = state.notifGate
    val controller = rememberNotificationPermissionController()
    // Per-gate phase; reset whenever a fresh run enters the gate. null = classify / nothing shown yet.
    var phase by remember(pending) { mutableStateOf<Phase?>(null) }

    LaunchedEffect(pending) {
        if (pending == null) { phase = null; return@LaunchedEffect }
        when (controller.status()) {
            // Already allowed, or a platform that doesn't gate it — no prompt; just run.
            NotificationPermissionStatus.GRANTED,
            NotificationPermissionStatus.NOT_APPLICABLE -> state.resolveNotifGate()
            NotificationPermissionStatus.DENIED -> phase = Phase.Rationale
        }
    }

    when (phase) {
        Phase.Rationale -> GateDialog(
            icon = CaIcons.hammer,
            iconTint = Ca.colors.accent,
            title = stringResource(Res.string.build_notif_title),
            message = stringResource(Res.string.build_notif_message),
            confirmLabel = stringResource(Res.string.allow),
            dismissLabel = stringResource(Res.string.build_notif_not_now),
            onConfirm = {
                phase = null // hide while the system prompt is up
                controller.request { granted ->
                    if (granted) state.resolveNotifGate() else phase = Phase.InProcessNotice
                }
            },
            onDismiss = { phase = Phase.InProcessNotice },
        )
        Phase.InProcessNotice -> GateDialog(
            icon = CaIcons.info,
            iconTint = Ca.colors.textSecondary,
            title = stringResource(Res.string.build_notif_inprocess_title),
            message = stringResource(Res.string.build_notif_inprocess_message),
            confirmLabel = stringResource(Res.string.got_it),
            dismissLabel = null,
            // Either button (or scrim tap) dismisses; the deferred run then starts in-process.
            onConfirm = { state.resolveNotifGate() },
            onDismiss = { state.resolveNotifGate() },
        )
        null -> {}
    }
}

private enum class Phase { Rationale, InProcessNotice }

@Composable
private fun GateDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    CenteredDialog(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = iconTint)
                Text(title, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(message, color = Ca.colors.textSecondary, style = Ca.type.footnote)
            Spacer(Modifier.height(16.dp))
            GateButton(confirmLabel, fill = Ca.colors.accent, fg = Ca.colors.textOnAccent, onClick = onConfirm)
            if (dismissLabel != null) {
                Spacer(Modifier.height(8.dp))
                GateButton(dismissLabel, fill = Ca.colors.surface3, fg = Ca.colors.textPrimary, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun GateButton(label: String, fill: Color, fg: Color, onClick: () -> Unit) {
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
        textAlign = TextAlign.Center,
    )
}
