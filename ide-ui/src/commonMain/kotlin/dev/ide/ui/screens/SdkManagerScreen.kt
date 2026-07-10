@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSdkDownload
import dev.ide.ui.backend.UiSdkPackage
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
    val iconBox = if (isMobilePlatform) 42 else 34

    Column(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, stringResource(Res.string.back), onBack, boxSize = iconBox)
            Text(stringResource(Res.string.sdk_manager_title), style = Ca.type.title3, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, modifier = Modifier.weight(1f))
            IconButtonCa(CaIcons.refresh, stringResource(Res.string.refresh), { scope.launch { reload() } }, boxSize = iconBox)
        }
        status?.let { Text(it, style = Ca.type.footnote, color = if (statusIsError) Ca.colors.error else Ca.colors.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Purpose: these downloads power editor docs, not building.
            Card {
                Text(stringResource(Res.string.sdk_sources_documentation), style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.sdk_sources_documentation_desc),
                    style = Ca.type.footnote, color = Ca.colors.textSecondary,
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
                Text(stringResource(Res.string.sdk_jdk_version, jdk?.version ?: "?"), style = Ca.type.subhead, color = Ca.colors.textPrimary)
                jdk?.home?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = Ca.type.footnote, color = Ca.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                if (jdk?.srcZip != null) {
                    StatusTag(CaIcons.check, stringResource(Res.string.sdk_jdk_sources_available), Ca.colors.run)
                } else {
                    Text(stringResource(Res.string.sdk_jdk_no_sources), style = Ca.type.footnote, color = Ca.colors.textSecondary)
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
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ca.colors.accent)
                    Text(stringResource(Res.string.sdk_loading_packages), style = Ca.type.footnote, color = Ca.colors.textSecondary)
                }
            } else if (packages.isEmpty()) {
                Text(stringResource(Res.string.sdk_no_packages), style = Ca.type.footnote, color = Ca.colors.textTertiary)
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
        }
    }
}

@Composable
private fun SectionHeader(text: String, small: Boolean = false) {
    Text(
        text,
        style = if (small) Ca.type.footnote else Ca.type.subhead,
        fontWeight = FontWeight.SemiBold,
        color = if (small) Ca.colors.textSecondary else Ca.colors.textPrimary,
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
}

@Composable
private fun DownloadRow(d: UiSdkDownload, onCancel: () -> Unit) {
    val active = d.status != "DONE" && d.status != "FAILED"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.label, style = Ca.type.body, color = Ca.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (d.status) {
                    "DONE" -> stringResource(Res.string.sdk_installed)
                    "FAILED" -> d.detail.ifEmpty { stringResource(Res.string.sdk_download_failed) }
                    "DOWNLOADING" -> if (d.detail.isNotEmpty()) stringResource(Res.string.sdk_downloading_detail, d.detail) else stringResource(Res.string.sdk_downloading)
                    "EXTRACTING" -> stringResource(Res.string.sdk_download_extracting)
                    "INSTALLING" -> stringResource(Res.string.sdk_download_installing)
                    else -> d.status
                }
                Text(
                    sub, style = Ca.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = when (d.status) { "DONE" -> Ca.colors.run; "FAILED" -> Ca.colors.error; else -> Ca.colors.textTertiary },
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
            Modifier.size(38.dp).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.sm)),
            contentAlignment = Alignment.Center,
        ) { Icon(CaIcons.androidLogo, null, Modifier.size(20.dp), tint = Ca.colors.accent) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = Ca.type.body, color = Ca.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                p.path + sizeLabel(p.sizeBytes),
                style = Ca.type.caption, color = Ca.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when {
            downloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ca.colors.accent)
                IconButtonCa(CaIcons.stop, stringResource(Res.string.cancel), onCancel, boxSize = if (isMobilePlatform) 40 else 32, iconSize = 18)
            }
            p.installed -> StatusTag(CaIcons.check, stringResource(Res.string.sdk_installed), Ca.colors.run)
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
        Text(label, style = Ca.type.footnote, fontWeight = FontWeight.Medium, color = color)
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
    val bg = if (accent && enabled) Ca.colors.accent else Ca.colors.surface3
    val fg = when { !enabled -> Ca.colors.textTertiary; accent -> Ca.colors.textOnAccent; else -> Ca.colors.textPrimary }
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
        Text(label, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** "  ·  12 MB" suffix, or empty when the size is unknown; "< 1 MB" for sub-megabyte packages. */
private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_048_576 -> "  ·  < 1 MB"
    else -> "  ·  ${bytes / 1_048_576} MB"
}
