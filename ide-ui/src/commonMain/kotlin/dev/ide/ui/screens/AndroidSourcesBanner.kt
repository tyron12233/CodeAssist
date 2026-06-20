package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ui.IdeUiState
import dev.ide.ui.backend.UiAndroidSourcesInfo
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

/**
 * A thin one-time banner offering to download the Android platform sources (so `android.*` APIs get
 * parameter names + javadoc). Shown only when an Android SDK is present, the sources aren't installed, and an
 * `sdkmanager` is available. Dismisses itself once a download is attempted.
 */
@Composable
internal fun AndroidSourcesBanner(state: IdeUiState) {
    var info by remember { mutableStateOf<UiAndroidSourcesInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { info = runCatching { state.backend.androidSourcesInfo() }.getOrNull() }

    val show = status != null || (info?.let { !it.installed && it.downloadable } == true)
    if (!show) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .background(Ca.colors.accent.copy(alpha = 0.08f), androidx.compose.foundation.shape.RoundedCornerShape(Ca.radius.sm))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status ?: "Android platform sources (${info?.platform}) aren't installed — needed for android.* parameter names & docs.",
            color = Ca.colors.textSecondary, style = Ca.type.footnote, modifier = Modifier.weight(1f),
        )
        if (status == null) {
            Text(
                if (busy) "Downloading…" else "Download",
                color = if (busy) Ca.colors.textTertiary else Ca.colors.accent,
                style = Ca.type.footnote,
                modifier = Modifier.then(
                    if (busy) Modifier else Modifier.clickable {
                        busy = true
                        scope.launch { status = runCatching { state.backend.downloadAndroidSources() }.getOrElse { "Download failed: ${it.message}" } }
                    },
                ),
            )
        }
    }
}
