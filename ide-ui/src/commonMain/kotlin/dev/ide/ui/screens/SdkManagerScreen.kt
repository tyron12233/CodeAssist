package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSdkDownload
import dev.ide.ui.backend.UiSdkPackage
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

/**
 * The SDK / toolchain manager: download **sources and documentation** (Android platform sources, JDK
 * `src.zip`) so the editor can show javadoc, parameter names, and go-to-source. Downloads run in the backend
 * and keep going after this screen is closed — the screen only observes [IdeBackend.sdkManagerState].
 */
@Composable
fun SdkManagerScreen(backend: IdeBackend, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress by backend.sdkManagerState.collectAsState()
    var packages by remember { mutableStateOf<List<UiSdkPackage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val jdk = remember { runCatching { backend.jdkInfo() }.getOrNull() }

    suspend fun reload() { loading = true; packages = runCatching { backend.sdkPackages() }.getOrDefault(emptyList()); loading = false }
    LaunchedEffect(Unit) { reload() }
    // Refresh the installed/incomplete flags whenever a download finishes (it ran in the background).
    val finishedCount = progress.downloads.count { it.status == "DONE" }
    LaunchedEffect(finishedCount) { if (finishedCount > 0) reload() }

    val activeIds = progress.downloads.filter { it.status != "DONE" && it.status != "FAILED" }.map { it.id }.toSet()

    Column(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack)
            Text("SDK Manager", style = Ca.type.title3, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary, modifier = Modifier.weight(1f))
            IconButtonCa(CaIcons.refresh, "Refresh", { scope.launch { reload() } })
        }
        status?.let { Text(it, style = Ca.type.footnote, color = Ca.colors.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Purpose — these downloads power editor docs, not building.
            Card {
                Text("Sources & documentation", style = Ca.type.subhead, fontWeight = FontWeight.SemiBold, color = Ca.colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Download SDK platform sources and JDK sources so the editor can show javadoc, parameter " +
                        "names, and go-to-source into the SDK. Downloads continue in the background — you can " +
                        "leave this screen and keep working.",
                    style = Ca.type.footnote, color = Ca.colors.textSecondary,
                )
            }

            // Active / recent downloads queue.
            if (progress.downloads.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Downloads", small = true)
                    Spacer(Modifier.weight(1f))
                    if (progress.downloads.any { it.status == "DONE" || it.status == "FAILED" }) {
                        ActionPill("Clear finished", enabled = true) { backend.clearSdkDownloads() }
                    }
                }
                Card {
                    progress.downloads.forEachIndexed { i, d ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        DownloadRow(d) { backend.cancelSdkDownload(d.id) }
                    }
                }
            }

            // JDK sources
            SectionHeader("JDK")
            Card {
                Text("JDK ${jdk?.version ?: "?"}", style = Ca.type.subhead, color = Ca.colors.textPrimary)
                Text(jdk?.home ?: "", style = Ca.type.footnote, color = Ca.colors.textTertiary, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                if (jdk?.srcZip != null) {
                    Text("Sources available — android.* and java.* docs are on.", style = Ca.type.footnote, color = Ca.colors.run)
                } else {
                    Text("No JDK sources found — download a JDK that bundles them:", style = Ca.type.footnote, color = Ca.colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (feature in listOf(17, 21)) {
                            val downloading = "jdk-$feature" in activeIds
                            ActionPill(if (downloading) "Downloading…" else "Download JDK $feature sources", enabled = !downloading) {
                                scope.launch { status = backend.downloadJdkSources(feature) }
                            }
                        }
                    }
                }
            }

            // Android platform sources (the documentation payload — platforms/build-tools live elsewhere).
            SectionHeader("Android SDK sources")
            if (loading && packages.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading package list…", style = Ca.type.footnote, color = Ca.colors.textSecondary)
                }
            } else if (packages.isEmpty()) {
                Text("No packages — check your connection, then Refresh.", style = Ca.type.footnote, color = Ca.colors.textTertiary)
            } else {
                for ((_, cat) in CATEGORIES) {
                    val group = packages.filter { it.category == cat }.sortedByDescending { it.path }
                    if (group.isEmpty()) continue
                    Card {
                        group.take(40).forEachIndexed { i, p ->
                            if (i > 0) Spacer(Modifier.height(6.dp))
                            PackageRow(p, downloading = p.path in activeIds) {
                                scope.launch { status = backend.installSdkPackage(p.path) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// The SDK Manager surfaces *sources only* (docs/go-to-source); the backend filters to the SOURCES category.
private val CATEGORIES = listOf(
    "Sources" to "SOURCES",
)

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
private fun DownloadRow(d: UiSdkDownload, onCancel: () -> Unit) {
    val active = d.status != "DONE" && d.status != "FAILED"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.label, style = Ca.type.body, color = Ca.colors.textPrimary)
                val sub = when (d.status) {
                    "DONE" -> "Installed"
                    "FAILED" -> d.detail.ifEmpty { "Failed" }
                    "DOWNLOADING" -> "Downloading${if (d.detail.isNotEmpty()) " · ${d.detail}" else ""}"
                    "EXTRACTING" -> "Extracting…"
                    "INSTALLING" -> "Installing…"
                    else -> d.status
                }
                Text(
                    sub, style = Ca.type.caption,
                    color = when (d.status) { "DONE" -> Ca.colors.run; "FAILED" -> Ca.colors.error; else -> Ca.colors.textTertiary },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (active) ActionPill("Cancel", enabled = true, onClick = onCancel)
        }
        if (active) {
            Spacer(Modifier.height(6.dp))
            if (d.fraction in 0.0..1.0) LinearProgressIndicator(progress = { d.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PackageRow(p: UiSdkPackage, downloading: Boolean, onInstall: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = Ca.type.body, color = Ca.colors.textPrimary)
            Text(p.path + (if (p.sizeBytes > 0) "  ·  ${p.sizeBytes / 1_048_576} MB" else ""), style = Ca.type.caption, color = Ca.colors.textTertiary)
        }
        Spacer(Modifier.width(8.dp))
        when {
            downloading -> Text("Downloading…", style = Ca.type.footnote, color = Ca.colors.textSecondary)
            p.incomplete -> ActionPill("Resume", enabled = p.installable, onClick = onInstall)
            p.installed -> Text("Installed", style = Ca.type.footnote, color = Ca.colors.run)
            else -> ActionPill("Install", enabled = p.installable, onClick = onInstall)
        }
    }
}

@Composable
private fun ActionPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Ca.type.footnote,
        fontWeight = FontWeight.SemiBold,
        color = if (enabled) Ca.colors.accent else Ca.colors.textTertiary,
        modifier = Modifier
            .background(if (enabled) Ca.colors.accentSoft else Ca.colors.surface3, RoundedCornerShape(Ca.radius.pill))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
