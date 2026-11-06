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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SdkFailureKind
import dev.ide.ui.backend.SdkFailures
import dev.ide.ui.backend.UiSdkDownload
import dev.ide.ui.backend.UiSdkFailure
import dev.ide.ui.backend.UiSdkPackage
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.CircularProgressIndicator
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.LinearProgressIndicator
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.*
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.platform.touchInput
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import org.jetbrains.compose.resources.stringResource

/**
 * The SDK / toolchain manager: Android platforms (the `android.jar` a module compiles against), Android
 * sources and JDK sources (editor docs, parameter names, go-to-source), and on desktop the platform tools.
 * Downloads run in the backend and keep going after this screen closes; the screen only observes
 * [IdeBackend.sdk]'s state.
 *
 * Each package list shows what matters up front (installed, in progress, failed, what the open project
 * compiles against, and the newest) and folds the remaining versions behind "Show all versions"
 * ([groupSdkPackages]). A failed download says why in plain terms and offers the fix that matches the cause,
 * with the raw error one tap away.
 */
@Composable
fun SdkManagerScreen(backend: IdeBackend, onBack: () -> Unit, onOpenStorage: (() -> Unit)? = null) {
    val state = rememberSdkManagerState(backend)
    ExpressiveScaffold(
        title = stringResource(Res.string.sdk_manager_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = state::reload) {
                Icon(CaIcons.refresh, stringResource(Res.string.refresh))
            }
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SdkManagerContent(state, onOpenStorage)
            // A native ad at the foot of this browse/download surface: dead time behind the download bars.
            AdSlot(AdPlacement.SDK_MANAGER)
        }
    }
    state.licensePrompt?.let { prompt ->
        LicenseDialog(prompt, onAccept = state::acceptLicense, onDecline = state::declineLicense)
    }
}

/** The screen body below the app bar, split out so a snapshot can render it against a fake backend. */
@Composable
internal fun SdkManagerContent(state: SdkManagerState, onOpenStorage: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            stringResource(Res.string.sdk_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val st = state.status) {
            is SdkStatus.LoadFailed -> SurfaceCard {
                FailureBlock(
                    title = stringResource(Res.string.sdk_load_failed_title),
                    failure = SdkFailures.classify(st.message),
                    raw = st.message,
                    onRetry = state::reload,
                    onRefresh = state::reload,
                    onOpenStorage = null,
                )
            }
            is SdkStatus.Message -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(CaIcons.info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(st.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            null -> Unit
        }

        DownloadsSection(state, onOpenStorage)

        val loadingFirst = state.loading && state.packages.isEmpty()
        if (loadingFirst) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.sdk_loading_packages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.packages.isEmpty() && state.status !is SdkStatus.LoadFailed) {
            Text(stringResource(Res.string.sdk_no_packages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }

        // Android platforms: the android.jar a module's compileSdk compiles and completes against.
        PackageSection(state, "PLATFORMS", stringResource(Res.string.sdk_platforms), stringResource(Res.string.sdk_platforms_desc))
        // Android platform sources: the documentation payload. Build tooling is bundled, never downloaded.
        PackageSection(state, "SOURCES", stringResource(Res.string.sdk_android_sources), null)
        // Platform tools (adb), desktop only: what Run uses to install and launch the app on a device.
        PackageSection(state, "PLATFORM_TOOLS", stringResource(Res.string.sdk_platform_tools), stringResource(Res.string.sdk_platform_tools_desc))

        JdkSection(state)
    }
}

// ---- downloads ----

@Composable
private fun DownloadsSection(state: SdkManagerState, onOpenStorage: (() -> Unit)?) {
    val downloads = state.progress.downloads
    if (downloads.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(Res.string.sdk_downloads), Modifier.weight(1f))
            if (state.hasFinishedDownloads) {
                TextAction(stringResource(Res.string.sdk_clear_finished), onClick = state::clearFinished)
            }
        }
        SurfaceCard {
            downloads.forEachIndexed { i, d ->
                if (i > 0) RowDivider()
                when (d.status) {
                    "FAILED" -> FailureBlock(
                        title = d.label,
                        failure = d.failure ?: SdkFailures.classify(d.detail),
                        raw = d.detail,
                        onRetry = { state.retry(d) },
                        onRefresh = state::reload,
                        onOpenStorage = onOpenStorage,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    else -> DownloadRow(d) { state.cancel(d.id) }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(d: UiSdkDownload, onCancel: () -> Unit) {
    val done = d.status == "DONE"
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (d.status) {
                    "DONE" -> stringResource(Res.string.sdk_installed)
                    "DOWNLOADING" -> if (d.detail.isNotEmpty()) stringResource(Res.string.sdk_downloading_detail, d.detail) else stringResource(Res.string.sdk_downloading)
                    "EXTRACTING" -> stringResource(Res.string.sdk_download_extracting)
                    "INSTALLING" -> stringResource(Res.string.sdk_download_installing)
                    else -> d.status
                }
                Text(
                    sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (done) Ide.colors.run else MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (done) Icon(CaIcons.check, null, Modifier.size(20.dp), tint = Ide.colors.run)
            else QuietIconButton(CaIcons.close, stringResource(Res.string.cancel), onClick = onCancel)
        }
        if (!done) {
            Spacer(Modifier.height(8.dp))
            if (d.fraction in 0.0..1.0) LinearProgressIndicator(progress = { d.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/**
 * A failure explained: [title] (what failed), a plain sentence for [failure], the action that fixes that cause,
 * and the raw error text ([raw]) behind a Details toggle with Copy, for a report or a search.
 */
@Composable
private fun FailureBlock(
    title: String,
    failure: UiSdkFailure,
    raw: String?,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onOpenStorage: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember(raw) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val cancelled = failure.kind == SdkFailureKind.CANCELLED
            Box(
                Modifier.size(28.dp).background(
                    if (cancelled) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.errorContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (cancelled) CaIcons.stop else CaIcons.warning, null, Modifier.size(16.dp),
                    tint = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(failureSentence(failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            when (failure.kind) {
                SdkFailureKind.STORAGE_FULL -> {
                    if (onOpenStorage != null) PillButton(stringResource(Res.string.sdk_action_free_space), CaIcons.box, accent = true, onClick = onOpenStorage)
                    PillButton(stringResource(Res.string.retry), CaIcons.refresh, accent = onOpenStorage == null, onClick = onRetry)
                }
                SdkFailureKind.NOT_FOUND -> PillButton(stringResource(Res.string.sdk_action_refresh), CaIcons.refresh, accent = true, onClick = onRefresh)
                SdkFailureKind.LICENSE -> PillButton(stringResource(Res.string.sdk_action_review_license), CaIcons.docText, accent = true, onClick = onRetry)
                SdkFailureKind.CANCELLED -> PillButton(stringResource(Res.string.sdk_resume), CaIcons.download, accent = true, onClick = onRetry)
                else -> PillButton(stringResource(Res.string.retry), CaIcons.refresh, accent = true, onClick = onRetry)
            }
            if (!raw.isNullOrBlank() && failure.kind != SdkFailureKind.CANCELLED) {
                TextAction(
                    stringResource(if (showDetails) Res.string.sdk_details_hide else Res.string.sdk_details_show),
                    icon = if (showDetails) CaIcons.chevronUp else CaIcons.chevronDown,
                ) { showDetails = !showDetails }
            }
        }
        if (showDetails && !raw.isNullOrBlank()) {
            val clipboard = LocalClipboardManager.current
            Column(
                Modifier.padding(start = 38.dp).fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.sm))
                    .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    raw, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp),
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextAction(stringResource(Res.string.copy), icon = CaIcons.copy) { clipboard.setText(AnnotatedString(raw)) }
                }
            }
        }
    }
}

/** The plain-language sentence for [failure]. */
@Composable
private fun failureSentence(failure: UiSdkFailure): String = when (failure.kind) {
    SdkFailureKind.OFFLINE -> stringResource(Res.string.sdk_fail_offline)
    SdkFailureKind.STORAGE_FULL -> stringResource(Res.string.sdk_fail_storage)
    SdkFailureKind.NOT_FOUND -> stringResource(Res.string.sdk_fail_not_found)
    SdkFailureKind.SERVER -> failure.httpCode?.let { stringResource(Res.string.sdk_fail_server_code, it) }
        ?: stringResource(Res.string.sdk_fail_server)
    SdkFailureKind.LICENSE -> stringResource(Res.string.sdk_fail_license)
    SdkFailureKind.CORRUPT -> stringResource(Res.string.sdk_fail_corrupt)
    SdkFailureKind.CANCELLED -> stringResource(Res.string.sdk_fail_cancelled)
    SdkFailureKind.UNKNOWN -> stringResource(Res.string.sdk_fail_unknown)
}

// ---- package lists ----

/**
 * One package category ([category]) as a section: what [groupSdkPackages] puts up front, then a
 * "Show all versions (N)" row that unfolds the rest. Renders nothing when the category has no packages.
 */
@Composable
private fun PackageSection(state: SdkManagerState, category: String, title: String, description: String?) {
    val packages = state.packages.filter { it.category == category }
    if (packages.isEmpty()) return
    val active = state.activeIds
    val group = groupSdkPackages(packages, state.neededLevels, active, state.failedIds)
    val expanded = category in state.expanded
    val rows = if (expanded) (group.shown + group.more).sortedWith(compareByDescending<UiSdkPackage> { sdkLevelOrder(it.path) }.thenByDescending { it.path })
               else group.shown
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SurfaceCard {
            rows.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                PackageRow(
                    p,
                    download = state.progress.downloads.firstOrNull { it.id == p.path && it.id in active },
                    latest = p.path == group.latest,
                    needed = p.path in group.needed,
                    onInstall = { state.install(p) },
                    onCancel = { state.cancel(p.path) },
                )
            }
            if (group.more.isNotEmpty()) {
                RowDivider()
                ExpanderRow(expanded, group.more.size) { state.toggleExpanded(category) }
            }
        }
    }
}

@Composable
private fun ExpanderRow(expanded: Boolean, hidden: Int, onToggle: () -> Unit) {
    val label = if (expanded) stringResource(Res.string.sdk_show_fewer) else stringResource(Res.string.sdk_show_all_versions, hidden)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.sm))
            .clickable(role = Role.Button, onClick = onToggle)
            .defaultMinSize(minHeight = if (touchInput) 48.dp else 40.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(if (expanded) CaIcons.chevronUp else CaIcons.chevronDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PackageRow(
    p: UiSdkPackage,
    download: UiSdkDownload?,
    latest: Boolean,
    needed: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = if (touchInput) 60.dp else 52.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Installed packages get the accent tile; the rest a neutral one, so what is on the device stands out.
        val tileBg = if (p.installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
        val tileFg = if (p.installed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.size(36.dp).background(tileBg, RoundedCornerShape(Ca.radius.sm)), contentAlignment = Alignment.Center) {
            Icon(if (p.category == "SOURCES") CaIcons.code else if (p.category == "PLATFORM_TOOLS") CaIcons.terminal else CaIcons.androidLogo, null, Modifier.size(18.dp), tint = tileFg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val sub = when {
                    download != null -> download.detail.takeIf { it.isNotEmpty() && download.status == "DOWNLOADING" }
                        ?: stringResource(if (download.status == "INSTALLING") Res.string.sdk_download_installing else Res.string.sdk_downloading)
                    p.installed -> stringResource(Res.string.sdk_installed)
                    else -> sizeLabel(p.sizeBytes)
                }
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = if (p.installed && download == null) Ide.colors.run else MaterialTheme.colorScheme.outline, maxLines = 1)
                }
                if (needed) Tag(stringResource(Res.string.sdk_tag_project), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                if (latest && !p.installed) Tag(stringResource(Res.string.sdk_tag_latest), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            download != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (download.fraction in 0.0..1.0) {
                    CircularProgressIndicator(progress = { download.fraction.toFloat() }, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                }
                QuietIconButton(CaIcons.close, stringResource(Res.string.cancel), onClick = onCancel)
            }
            p.installed -> Box(Modifier.size(if (touchInput) 44.dp else 36.dp), contentAlignment = Alignment.Center) {
                Icon(CaIcons.check, stringResource(Res.string.sdk_installed), Modifier.size(20.dp), tint = Ide.colors.run)
            }
            p.incomplete -> QuietIconButton(CaIcons.refresh, stringResource(Res.string.sdk_resume), enabled = p.installable, emphasized = true, onClick = onInstall)
            else -> QuietIconButton(CaIcons.download, stringResource(Res.string.install), enabled = p.installable, emphasized = true, onClick = onInstall)
        }
    }
}

// ---- JDK ----

@Composable
private fun JdkSection(state: SdkManagerState) {
    val jdk = state.jdk
    val activeIds = state.activeIds
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.sdk_jdk))
        SurfaceCard(Modifier.padding(vertical = 12.dp)) {
            if (jdk?.androidRuntime == true) {
                // ART reports java.home /apex/com.android.art + java.version 0: it is the Android runtime, not a
                // JDK. Label it plainly (no "JDK 0", no /apex path) and point at the JDK-source download below.
                Text(stringResource(Res.string.sdk_jdk_android_runtime), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Text(stringResource(Res.string.sdk_jdk_version, jdk?.version ?: "?"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                jdk?.home?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            if (jdk?.srcZip != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(CaIcons.check, null, Modifier.size(16.dp), tint = Ide.colors.run)
                    Text(stringResource(Res.string.sdk_jdk_sources_available), style = MaterialTheme.typography.bodyMedium, color = Ide.colors.run)
                }
            } else {
                val noSources = if (jdk?.androidRuntime == true) stringResource(Res.string.sdk_jdk_android_runtime_desc)
                                else stringResource(Res.string.sdk_jdk_no_sources)
                Text(noSources, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (feature in listOf(17, 21)) {
                        val downloading = "jdk-$feature" in activeIds
                        PillButton(
                            if (downloading) stringResource(Res.string.sdk_jdk_downloading, feature) else stringResource(Res.string.sdk_jdk_sources, feature),
                            if (downloading) null else CaIcons.download,
                            accent = false, enabled = !downloading,
                        ) { state.downloadJdkSources(feature) }
                    }
                }
            }
        }
    }
}

// ---- license ----

/** The license a package needs accepted before it installs, shown in full with Accept and Decline. */
@Composable
private fun LicenseDialog(prompt: LicensePrompt, onAccept: () -> Unit, onDecline: () -> Unit) {
    CenteredDialog(visible = true, onDismiss = onDecline) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(horizontal = 16.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.sdk_license_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(prompt.pkg.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md))
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(
                    prompt.text ?: stringResource(Res.string.sdk_license_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(Res.string.sdk_license_decline), null, accent = false, onClick = onDecline)
                PillButton(stringResource(Res.string.sdk_license_accept), CaIcons.check, accent = true, enabled = prompt.text != null, onClick = onAccept)
            }
        }
    }
}

// ---- building blocks ----

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.lg))
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .then(modifier),
    ) { content() }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

/** A small rounded label next to a package's size ("Latest", "Your project"). */
@Composable
private fun Tag(text: String, bg: Color, fg: Color) {
    Box(Modifier.background(bg, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/**
 * A round icon action for a list row. [emphasized] gives it a tonal fill (Install, Resume) so the action reads
 * at a glance without a filled accent pill on every row; otherwise it is a bare icon (Cancel).
 */
@Composable
private fun QuietIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean = true, emphasized: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val size = if (touchInput) 44.dp else 36.dp
    val bg = if (emphasized && enabled) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.outline
        emphasized -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier.size(size)
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .clip(CircleShape)
            .background(bg)
            .then(if (enabled) Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = fg)
    }
}

/** A low-emphasis text action (Clear finished, Details, Copy). */
@Composable
private fun TextAction(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(Ca.radius.control))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = if (touchInput) 40.dp else 32.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A touch-sized action button (>= 44dp tall on mobile). [accent] fills it with the accent color for the one
 * action that fixes a problem (Try again, Free up space); otherwise it's a neutral surface chip.
 */
@Composable
private fun PillButton(label: String, icon: ImageVector?, accent: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val h = if (touchInput) 44.dp else 36.dp
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

/** "12 MB", "< 1 MB", or empty when the size is unknown. */
private fun sizeLabel(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_048_576 -> "< 1 MB"
    else -> "${bytes / 1_048_576} MB"
}
