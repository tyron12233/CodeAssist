package dev.ide.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AgentService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiMcpRelayStatus
import dev.ide.ui.ext.ScreenRegistry
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.dismiss
import dev.ide.ui.generated.resources.edchrome_claude_connected
import dev.ide.ui.generated.resources.edchrome_claude_working
import dev.ide.ui.generated.resources.home_claude_action
import dev.ide.ui.generated.resources.home_claude_body
import dev.ide.ui.generated.resources.home_claude_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/** Whether Claude is on the other end of the IDE's MCP link right now, as the editor's top bar shows it. */
enum class ClaudeLinkStatus { Off, Connected, Working }

/**
 * The "Use with Claude" feature as the shell sees it. [available] is false where neither the local MCP server
 * nor the hosted relay exists in this build, or the agent plugin that owns the screen is turned off; then no
 * entry point leads to it. [enabled] is true while either link is switched on.
 */
data class ClaudeLinkState(
    val available: Boolean,
    val enabled: Boolean,
    val status: ClaudeLinkStatus,
)

/**
 * Reads the link's state from [backend]. Only the relay reports a live connection and the requests in flight;
 * the local server reports only that it is listening, which says nothing about whether a client is attached,
 * so it counts toward [ClaudeLinkState.enabled] and not toward [ClaudeLinkState.status].
 */
@Composable
fun rememberClaudeLinkState(backend: IdeBackend): ClaudeLinkState {
    val host by backend.agent.mcpHost.collectAsState()
    val relay by backend.agent.mcpRelay.collectAsState()
    val registered = ScreenRegistry.find(AgentService.SCREEN_USE_WITH_CLAUDE) != null
    val connected = relay.enabled && relay.status == UiMcpRelayStatus.CONNECTED
    return ClaudeLinkState(
        available = registered && (host.supported || relay.supported),
        enabled = host.running || relay.enabled,
        status = when {
            !connected -> ClaudeLinkStatus.Off
            relay.inFlight > 0 -> ClaudeLinkStatus.Working
            else -> ClaudeLinkStatus.Connected
        },
    )
}

/**
 * The top bar's sign that Claude can reach the open project: a green dot while connected, and a pulsing ring
 * around it while Claude is making changes. [compact] keeps only the dot, because on a phone's bar any text here
 * comes out of the project name; the sentence stays as the chip's description. Draws nothing when [status] is
 * [ClaudeLinkStatus.Off].
 *
 * The pulse is an infinite transition, so it holds still where the system asks for reduced motion.
 */
@Composable
fun ClaudeLinkChip(status: ClaudeLinkStatus, compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (status == ClaudeLinkStatus.Off) return
    val working = status == ClaudeLinkStatus.Working
    val sentence = stringResource(if (working) Res.string.edchrome_claude_working else Res.string.edchrome_claude_connected)
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.pill)
    val fill = if (working) scheme.tertiaryContainer else scheme.surfaceContainerHigh
    val ink = if (working) scheme.onTertiaryContainer else scheme.onSurfaceVariant
    Row(
        modifier.clip(shape).clickable(onClick = onClick).background(fill, shape)
            .semantics { contentDescription = sentence }
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(working)
        if (!compact) Text(
            sentence, color = ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusDot(working: Boolean) {
    val green = Ide.colors.success
    if (!working) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(green))
        return
    }
    val pulse = rememberInfiniteTransition(label = "claudePulse")
    val t by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "claudePulseT",
    )
    val ring = MaterialTheme.colorScheme.tertiary
    Box(
        Modifier.size(8.dp).drawBehind {
            // A ring that grows out of the dot and fades, behind the dot itself.
            val r = size.minDimension / 2f
            drawCircle(ring.copy(alpha = 0.55f * (1f - t)), radius = r * (1f + 0.9f * t))
            drawCircle(green, radius = r)
        },
    )
}

/**
 * Home's one-time announcement of the "Use with Claude" screen: what it is in a sentence, a button that opens
 * it, and a close button. The host decides when it shows and remembers that it was answered.
 */
@Composable
fun ClaudeAnnouncementCard(onSetUp: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(scheme.primaryContainer, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) { Icon(CaIcons.share, null, Modifier.size(20.dp), tint = scheme.onPrimaryContainer) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.home_claude_title),
                color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(Res.string.home_claude_body), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Box(Modifier.padding(top = 6.dp)) { PrimaryButton(stringResource(Res.string.home_claude_action), onSetUp) }
        }
        IconButtonCa(CaIcons.close, stringResource(Res.string.dismiss), onDismiss, iconSize = 16, boxSize = 30)
    }
}
