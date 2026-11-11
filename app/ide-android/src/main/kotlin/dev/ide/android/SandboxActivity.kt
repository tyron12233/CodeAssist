package dev.ide.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
 * The plugin sandbox's marker: its status-bar band tinted, with the plugin under test named in it, so it is never
 * mistaken for the IDE it was started from. Drawn over the status bar only, so the app's own layout is untouched.
 */
@Composable
internal fun SandboxBand() {
    val context = LocalContext.current
    val name by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                SandboxProcess.currentApk(context)?.let { DevApkPluginSource(context, it).manifest()?.name }
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(SANDBOX_BAND),
    ) {
        BandLabel(name?.let { "Plugin sandbox · $it" } ?: "Plugin sandbox")
    }
}

/**
 * The same band for the agent's outside connections: while the Claude link or the local MCP server is on, the
 * status bar says so, because another app can then change the open project without asking and nothing else on
 * screen shows it. It names the state that matters: Claude at work, connected and idle, or not connected.
 */
@Composable
internal fun McpBand(agent: dev.ide.ui.backend.AgentService) {
    val relay by agent.mcpRelay.collectAsState()
    val host by agent.mcpHost.collectAsState()
    val (label, color) = mcpBandContent(relay, host.running) ?: return
    Box(
        Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(color),
    ) {
        BandLabel(label)
    }
}

/**
 * A status-bar band's label, where it can be read. The middle of the bar when nothing is there: the clock sits
 * at one end and the system icons at the other, and on a narrow phone (360dp) a full-width label ran under the
 * icons. A camera cutout in the middle hides a centred label, so with one the label goes in the wider of the two
 * gaps beside it.
 */
@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.BoxScope.BandLabel(text: String) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val width = constraints.maxWidth.toFloat()
        val (start, end) = bandLabelSpan(width, topCutout(view, width))
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(
            Modifier
                .fillMaxHeight()
                .offset { androidx.compose.ui.unit.IntOffset(start.toInt(), 0) }
                .width(with(density) { (end - start).toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** The horizontal extent, in px, of a display cutout that lies across the middle of the top edge; null for none. */
private fun topCutout(view: android.view.View, width: Float): Pair<Float, Float>? {
    if (android.os.Build.VERSION.SDK_INT < 28 || width <= 0f) return null
    // The window handle Android draws there on a device with desktop windowing is not reported as an inset, so
    // a label can still sit under that one.
    val rect = view.rootWindowInsets?.displayCutout?.boundingRects
        ?.firstOrNull { it.top <= 0 && it.left < width * 0.71f && it.right > width * 0.29f } ?: return null
    return rect.left.toFloat() to rect.right.toFloat()
}

/**
 * Where a band's label goes across a bar [width] px wide: the middle 42% with no [cutout], else the wider gap
 * between the cutout and the clock (the first fifth of the bar) or the system icons (the last fifth).
 */
internal fun bandLabelSpan(width: Float, cutout: Pair<Float, Float>?): Pair<Float, Float> {
    if (cutout == null) return width * 0.29f to width * 0.71f
    val margin = width * 0.02f
    val left = width * 0.20f to cutout.first - margin
    val right = cutout.second + margin to width * 0.80f
    return if (right.second - right.first > left.second - left.first) right else left
}

/** What the band says and its colour, or null when nothing outside is connected. The relay outranks the local server. */
internal fun mcpBandContent(relay: dev.ide.ui.backend.UiMcpRelay, localRunning: Boolean): Pair<String, Color>? = when {
    relay.enabled -> when (relay.status) {
        dev.ide.ui.backend.UiMcpRelayStatus.CONNECTED ->
            if (relay.inFlight > 0) "Claude is working" to MCP_BAND_ACTIVE else "Claude link on" to MCP_BAND_ON
        dev.ide.ui.backend.UiMcpRelayStatus.SIGNED_OUT -> "Claude link · sign in" to MCP_BAND_WAITING
        dev.ide.ui.backend.UiMcpRelayStatus.IN_USE, dev.ide.ui.backend.UiMcpRelayStatus.REPLACED -> "Claude link · stopped" to MCP_BAND_WAITING
        else -> "Claude link · connecting" to MCP_BAND_WAITING
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
