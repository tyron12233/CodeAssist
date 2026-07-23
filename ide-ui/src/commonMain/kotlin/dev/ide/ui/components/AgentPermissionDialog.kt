package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentPermissionDecision
import dev.ide.ui.backend.UiAgentPermissionRequest
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.chat_allow
import dev.ide.ui.generated.resources.chat_allow_session
import dev.ide.ui.generated.resources.chat_deny
import dev.ide.ui.generated.resources.chat_perm_title
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The agent's write-permission prompt (ASK_EACH mode): while a mutating tool call is blocked awaiting a
 * decision, this overlays and routes the answer back. Observes [IdeBackend.agent] and mirrors the run
 * sandbox's [PermissionDialog]. Dismissing denies, the safe default. Hosted app-wide in [dev.ide.ui.AppOverlays].
 */
@Composable
fun AgentPermissionDialog(backend: IdeBackend) {
    val request by backend.agent.permissionRequest.collectAsState()
    var shown by remember { mutableStateOf<UiAgentPermissionRequest?>(null) }
    if (request != null) shown = request

    fun answer(decision: UiAgentPermissionDecision) { shown?.let { backend.agent.answerPermission(it.id, decision) } }

    CenteredDialog(visible = request != null, onDismiss = { answer(UiAgentPermissionDecision.DENY) }) {
        shown?.let { req -> AgentPermissionCard(req, ::answer) }
    }
}

@Composable
private fun AgentPermissionCard(req: UiAgentPermissionRequest, answer: (UiAgentPermissionDecision) -> Unit) {
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.xl))
            .border(1.dp, Ca.colors.glassEdge, RoundedCornerShape(Ca.radius.xl))
            .padding(20.dp),
    ) {
        Text(stringResource(Res.string.chat_perm_title), color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(req.summary, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
        req.path?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = Ca.colors.textTertiary, style = Ca.type.caption)
        }
        Spacer(Modifier.height(16.dp))

        AgentPermButton(stringResource(Res.string.chat_allow), AgentPermKind.Accent) { answer(UiAgentPermissionDecision.ALLOW_ONCE) }
        Spacer(Modifier.height(8.dp))
        AgentPermButton(stringResource(Res.string.chat_allow_session), AgentPermKind.Neutral) { answer(UiAgentPermissionDecision.ALLOW_SESSION) }
        Spacer(Modifier.height(8.dp))
        AgentPermButton(stringResource(Res.string.chat_deny), AgentPermKind.Danger) { answer(UiAgentPermissionDecision.DENY) }
    }
}

private enum class AgentPermKind { Accent, Neutral, Danger }

@Composable
private fun AgentPermButton(label: String, kind: AgentPermKind, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val fill = when (kind) {
        AgentPermKind.Accent -> Ca.colors.accent
        AgentPermKind.Danger -> Ca.colors.error.copy(alpha = 0.16f)
        AgentPermKind.Neutral -> Ca.colors.surface3
    }
    val fg = when (kind) {
        AgentPermKind.Accent -> Ca.colors.textOnAccent
        AgentPermKind.Danger -> Ca.colors.error
        AgentPermKind.Neutral -> Ca.colors.textPrimary
    }
    val shape = RoundedCornerShape(Ca.radius.control)
    Box(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .background(fill, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
