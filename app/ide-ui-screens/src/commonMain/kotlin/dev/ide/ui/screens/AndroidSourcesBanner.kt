package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
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
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.sources_download
import dev.ide.ui.generated.resources.sources_download_failed
import dev.ide.ui.generated.resources.sources_downloading
import dev.ide.ui.generated.resources.sources_not_installed
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The Android-platform-sources offer: downloading them gives `android.*` APIs their parameter names and
 * javadoc. Null unless an Android SDK is present, the sources are not installed, and an `sdkmanager` is
 * available to fetch them.
 *
 * Keeps its own busy/result state, so the strip does not have to know that its action is asynchronous.
 */
@Composable
internal fun androidSourcesNotice(state: IdeUiState): EditorNotice? {
    var info by remember { mutableStateOf<UiAndroidSourcesInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val downloadFailedTemplate = stringResource(Res.string.sources_download_failed)
    LaunchedEffect(Unit) { info = runCatching { state.backend.sdk.androidSourcesInfo() }.getOrNull() }

    if (dismissed) return null
    val show = status != null || (info?.let { !it.installed && it.downloadable } == true)
    if (!show) return null
    val platform = info?.platform.orEmpty()
    val downloading = stringResource(Res.string.sources_downloading)
    val download = stringResource(Res.string.sources_download)
    return EditorNotice(
        id = "androidsources",
        level = NoticeLevel.Info,
        summary = status ?: stringResource(Res.string.sources_not_installed, platform),
        actionLabel = if (status != null) null else if (busy) downloading else download,
        onAction = if (status != null || busy) null else ({
            busy = true
            scope.launch {
                status = runCatching { state.backend.sdk.downloadAndroidSources() }
                    .getOrElse { downloadFailedTemplate.replace("%1\$s", it.message.toString()) }
                busy = false
            }
        }),
        onDismiss = { dismissed = true },
    )
}
