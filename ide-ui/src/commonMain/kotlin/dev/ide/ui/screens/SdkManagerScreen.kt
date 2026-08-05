@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import dev.ide.ui.components.ExpressiveScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSdkDownload
import dev.ide.ui.backend.UiSdkPackage
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.install
import dev.ide.ui.generated.resources.refresh
import dev.ide.ui.generated.resources.sdk_android_sources
import dev.ide.ui.generated.resources.sdk_clear_finished
import dev.ide.ui.generated.resources.sdk_could_not_load
import dev.ide.ui.generated.resources.sdk_download_extracting
import dev.ide.ui.generated.resources.sdk_download_failed
import dev.ide.ui.generated.resources.sdk_download_installing
import dev.ide.ui.generated.resources.sdk_downloading
import dev.ide.ui.generated.resources.sdk_downloading_detail
import dev.ide.ui.generated.resources.sdk_downloads
import dev.ide.ui.generated.resources.sdk_installed
import dev.ide.ui.generated.resources.sdk_jdk
import dev.ide.ui.generated.resources.sdk_jdk_android_runtime
import dev.ide.ui.generated.resources.sdk_jdk_android_runtime_desc
import dev.ide.ui.generated.resources.sdk_jdk_downloading
import dev.ide.ui.generated.resources.sdk_jdk_no_sources
import dev.ide.ui.generated.resources.sdk_jdk_sources
import dev.ide.ui.generated.resources.sdk_jdk_sources_available
import dev.ide.ui.generated.resources.sdk_jdk_version
import dev.ide.ui.generated.resources.sdk_loading_packages
import dev.ide.ui.generated.resources.sdk_manager_title
import dev.ide.ui.generated.resources.sdk_no_packages
import dev.ide.ui.generated.resources.sdk_resume
import dev.ide.ui.generated.resources.sdk_sources_documentation
import dev.ide.ui.generated.resources.sdk_sources_documentation_desc
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The SDK / toolchain manager: download **sources and documentation** (Android platform sources, JDK
 * `src.zip`) so the editor can show javadoc, parameter names, and go-to-source. Downloads run in the backend
 * and keep going after this screen is closed; the screen only observes [IdeBackend.sdkManagerState].
 *
 * Tuned for touch: full-width rows with large (>= 44dp) action buttons, leading icons for scannability,
 * single-line ellipsized paths, and wrapping button groups so nothing clips on a narrow phone screen.
 */
@Composable
fun SdkManagerScreen(backend: IdeBackend, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress by backend.sdk.sdkManagerState.collectAsState()
    var packages by remember { mutableStateOf<List<UiSdkPackage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    val jdk = remember { runCatching { backend.sdk.jdkInfo() }.getOrNull() }
    val couldNotLoadMsg = stringResource(Res.string.sdk_could_not_load)

    suspend fun reload() {
        loading = true
        status = null
        val result = runCatching { backend.sdk.sdkPackages() }
        packages = result.getOrDefault(emptyList())
        statusIsError = result.isFailure
        if (result.isFailure) status = result.exceptionOrNull()?.message ?: couldNotLoadMsg
        loading = false
    }
    LaunchedEffect(Unit) { reload() }
    // Refresh the installed/incomplete flags whenever a download finishes (it ran in the background).
    val finishedCount = progress.downloads.count { it.status == "DONE" }
    LaunchedEffect(finishedCount) { if (finishedCount > 0) reload() }

    val activeIds = progress.downloads.filter { it.status != "DONE" && it.status != "FAILED" }.map { it.id }.toSet()
    ExpressiveScaffold(
        title = stringResource(Res.string.sdk_manager_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { scope.launch { reload() } }) {
                Icon(CaIcons.refresh, stringResource(Res.string.refresh))
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            // Purpose: these downloads power editor docs, not building.
            Card {
                Text(stringResource(Res.string.sdk_sources_documentation), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.sdk_sources_documentation_desc),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Active / recent downloads queue.
            if (progress.downloads.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(stringResource(Res.string.sdk_downloads), small = true)
                    Spacer(Modifier.weight(1f))
                    if (progress.downloads.any { it.status == "DONE" || it.status == "FAILED" }) {
                        PillButton(stringResource(Res.string.sdk_clear_finished), null, accent = false) { backend.sdk.clearSdkDownloads() }
                    }
                }
                Card {
                    progress.downloads.forEachIndexed { i, d ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        DownloadRow(d) { backend.sdk.cancelSdkDownload(d.id) }
                    }
                }
            }

            // JDK sources
            SectionHeader(stringResource(Res.string.sdk_jdk))
            Card {
                if (jdk?.androidRuntime == true) {
                    // ART reports java.home /apex/com.android.art + java.version 0 — it is the Android runtime,
                    // not a JDK. Label it plainly (no bogus "JDK 0", no /apex path) and point at the JDK-source
                    // download below, which is the meaningful action here (java.* docs for the bundled toolchain).
                    Text(stringResource(Res.string.sdk_jdk_android_runtime), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    Text(stringResource(Res.string.sdk_jdk_version, jdk?.version ?: "?"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    jdk?.home?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (jdk?.srcZip != null) {
                    StatusTag(CaIcons.check, stringResource(Res.string.sdk_jdk_sources_available), Ide.colors.run)
                } else {
                    // ART: explain why there's no JDK and what the buttons do; JVM: the generic no-sources prompt.
                    val noSources = if (jdk?.androidRuntime == true) stringResource(Res.string.sdk_jdk_android_runtime_desc)
                                    else stringResource(Res.string.sdk_jdk_no_sources)
                    Text(noSources, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    // FlowRow so the buttons wrap onto another line on a narrow phone instead of clipping.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (feature in listOf(17, 21)) {
                            val downloading = "jdk-$feature" in activeIds
                            PillButton(
                                if (downloading) stringResource(Res.string.sdk_jdk_downloading, feature) else stringResource(Res.string.sdk_jdk_sources, feature),
                                if (downloading) null else CaIcons.download,
                                accent = true, enabled = !downloading,
                            ) { scope.launch { status = backend.sdk.downloadJdkSources(feature) } }
                        }
                    }
                }
            }

            // Android platform sources (the documentation payload). Build tooling is bundled, never downloaded.
            SectionHeader(stringResource(Res.string.sdk_android_sources))
            if (loading && packages.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.sdk_loading_packages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (packages.isEmpty()) {
                Text(stringResource(Res.string.sdk_no_packages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            } else {
                Card {
                    val sorted = packages.sortedByDescending { it.path }
                    sorted.forEachIndexed { i, p ->
                        if (i > 0) { Spacer(Modifier.height(4.dp)); RowDivider(); Spacer(Modifier.height(4.dp)) }
                        PackageRow(p, downloading = p.path in activeIds, onInstall = {
                            scope.launch { status = backend.sdk.installSdkPackage(p.path) }
                        }, onCancel = { backend.sdk.cancelSdkDownload(p.path) })
                    }
                }
            }

            // A native ad at the foot of this browse/download surface — dead time behind the download bars.
            AdSlot(AdPlacement.SDK_MANAGER)
        }
    }
}

@Composable
private fun SectionHeader(text: String, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (small) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun DownloadRow(d: UiSdkDownload, onCancel: () -> Unit) {
    val active = d.status != "DONE" && d.status != "FAILED"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (d.status) {
                    "DONE" -> stringResource(Res.string.sdk_installed)
                    "FAILED" -> d.detail.ifEmpty { stringResource(Res.string.sdk_download_failed) }
                    "DOWNLOADING" -> if (d.detail.isNotEmpty()) stringResource(Res.string.sdk_downloading_detail, d.detail) else stringResource(Res.string.sdk_downloading)
                    "EXTRACTING" -> stringResource(Res.string.sdk_download_extracting)
                    "INSTALLING" -> stringResource(Res.string.sdk_download_installing)
                    else -> d.status
                }
                Text(
                    sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when (d.status) { "DONE" -> Ide.colors.run; "FAILED" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.outline },
                )
            }
            Spacer(Modifier.width(10.dp))
            if (active) PillButton(stringResource(Res.string.cancel), CaIcons.stop, accent = false, onClick = onCancel)
        }
        if (active) {
            Spacer(Modifier.height(8.dp))
            if (d.fraction in 0.0..1.0) LinearProgressIndicator(progress = { d.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PackageRow(p: UiSdkPackage, downloading: Boolean, onInstall: () -> Unit, onCancel: () -> Unit) {
    val rowMin = if (isMobilePlatform) 56.dp else 48.dp
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = rowMin).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.sm)),
            contentAlignment = Alignment.Center,
        ) { Icon(CaIcons.androidLogo, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                p.path + sizeLabel(p.sizeBytes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            downloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                IconButtonCa(CaIcons.stop, stringResource(Res.string.cancel), onCancel, boxSize = if (isMobilePlatform) 40 else 32, iconSize = 18)
            }
            p.installed -> StatusTag(CaIcons.check, stringResource(Res.string.sdk_installed), Ide.colors.run)
            p.incomplete -> PillButton(stringResource(Res.string.sdk_resume), CaIcons.refresh, accent = true, enabled = p.installable, onClick = onInstall)
            else -> PillButton(stringResource(Res.string.install), CaIcons.download, accent = true, enabled = p.installable, onClick = onInstall)
        }
    }
}

/** A glanceable status line: a small leading glyph plus a colored label. */
@Composable
private fun StatusTag(icon: ImageVector, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = color)
    }
}

/**
 * A touch-sized action button (>= 44dp tall on mobile). [accent] fills it with the accent color for primary
 * actions (Install/Resume); otherwise it's a neutral surface chip (Cancel/Clear).
 */
@Composable
private fun PillButton(label: String, icon: ImageVector?, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val h = if (isMobilePlatform) 44.dp else 36.dp
    val bg = if (accent && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = when { !enabled -> MaterialTheme.colorScheme.outline; accent -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface }
    Row(
        Modifier.height(h)
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .background(bg, RoundedCornerShape(Ca.radius.control))
            .then(if (enabled) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** "  ·  12 MB" suffix, or empty when the size is unknown; "< 1 MB" for sub-megabyte packages. */
private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_048_576 -> "  ·  < 1 MB"
    else -> "  ·  ${bytes / 1_048_576} MB"
}
