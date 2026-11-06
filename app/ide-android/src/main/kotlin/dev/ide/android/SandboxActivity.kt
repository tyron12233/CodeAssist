package dev.ide.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name?.let { "Plugin sandbox · $it" } ?: "Plugin sandbox",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            // The middle of the bar only: the clock sits at one end and the system icons at the other, and on a
            // narrow phone (360dp) the full label ran under the icons.
            modifier = Modifier.fillMaxWidth(0.42f),
        )
    }
}

/** Amber: unlike every theme the IDE ships, so the band reads as "not your IDE" in light and dark alike. */
private val SANDBOX_BAND = Color(0xFFB45309)
