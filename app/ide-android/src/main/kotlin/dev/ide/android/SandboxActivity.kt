package dev.ide.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.android.plugins.DevApkPluginSource
import dev.ide.android.plugins.SandboxProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The plugin sandbox's window: the IDE itself, run in the `:sandbox` process the manifest gives this activity,
 * in a task of its own so it sits beside the real IDE in Recents rather than replacing it. Everything that makes
 * it a sandbox (its own home, the plugin build it loads, no ads, no analytics) is decided by the process it
 * runs in; see [dev.ide.android.plugins.SandboxProcess].
 */
class SandboxActivity : MainActivity()

/**
 * The marker band for a window that must not be mistaken for the plain IDE: while the plugin sandbox is running,
 * or the Claude link or the local MCP server is on (another app can then change the open project without asking,
 * and nothing else on screen shows it). The status bar is tinted and a strip carrying the label sits directly
 * BELOW it, so the text is never under the clock, the notification and system icons, or a camera cutout, whatever
 * the device puts in the bar. [content] gets the rest of the window and its status-bar inset already consumed, so
 * the app's own `safeDrawing` padding does not pad for the bar a second time.
 */
@Composable
internal fun StatusBand(inSandbox: Boolean, agent: dev.ide.ui.backend.AgentService, content: @Composable () -> Unit) {
    val relay by agent.mcpRelay.collectAsState()
    val host by agent.mcpHost.collectAsState()
    val context = LocalContext.current
    val sandboxName by produceState<String?>(null, inSandbox) {
        if (!inSandbox) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                SandboxProcess.currentApk(context)?.let { DevApkPluginSource(context, it).manifest()?.name }
            }.getOrNull()
        }
    }
    val band: Pair<String, Color>? =
        if (inSandbox) (sandboxName?.let { "Plugin sandbox · $it" } ?: "Plugin sandbox") to SANDBOX_BAND
        else mcpBandContent(relay, host.running)
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        if (band != null) {
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(band.second))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(band.second)
                    // Beside a cutout in landscape, or a side bar: the label stays in the safe area.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = band.first, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(if (band != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
            content()
        }
    }
}

/** What the band says and its colour, or null when nothing outside is connected. The relay outranks the local server. */
internal fun mcpBandContent(relay: dev.ide.ui.backend.UiMcpRelay, localRunning: Boolean): Pair<String, Color>? = when {
    relay.enabled -> when (relay.status) {
        dev.ide.ui.backend.UiMcpRelayStatus.CONNECTED ->
            if (relay.inFlight > 0) "AI app is working" to MCP_BAND_ACTIVE else "AI link on" to MCP_BAND_ON
        dev.ide.ui.backend.UiMcpRelayStatus.SIGNED_OUT -> "AI link · sign in" to MCP_BAND_WAITING
        dev.ide.ui.backend.UiMcpRelayStatus.IN_USE, dev.ide.ui.backend.UiMcpRelayStatus.REPLACED -> "AI link · stopped" to MCP_BAND_WAITING
        else -> "AI link · connecting" to MCP_BAND_WAITING
    }
    localRunning -> "MCP server on" to MCP_BAND_ON
    else -> null
}

/** Teal while reachable, brighter while Claude is at work, grey while the link is on but not connected. */
private val MCP_BAND_ON = Color(0xFF0F766E)
private val MCP_BAND_ACTIVE = Color(0xFF7C3AED)
private val MCP_BAND_WAITING = Color(0xFF52525B)

/** Amber: unlike every theme the IDE ships, so the band reads as "not your IDE" in light and dark alike. */
private val SANDBOX_BAND = Color(0xFFB45309)
