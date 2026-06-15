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
import dev.ide.ui.backend.UiSdkPackage
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch

/**
 * The SDK / toolchain manager: download Android SDK packages (platforms, build-tools, sources, command-line
 * tools) and JDK sources. Talks only to [IdeBackend]; the download work + progress live in the backend.
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

        if (progress.busy) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(progress.message, style = Ca.type.footnote, color = Ca.colors.textSecondary)
                Spacer(Modifier.height(4.dp))
                if (progress.fraction in 0.0..1.0) LinearProgressIndicator(progress = { progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        status?.let { Text(it, style = Ca.type.footnote, color = Ca.colors.accent, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                            ActionPill("Download JDK $feature sources", enabled = !progress.busy) {
                                scope.launch { status = backend.downloadJdkSources(feature) }
                            }
                        }
                    }
                }
            }

            // Android packages, grouped
            SectionHeader("Android SDK")
            if (loading && packages.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading package list…", style = Ca.type.footnote, color = Ca.colors.textSecondary)
                }
            } else if (packages.isEmpty()) {
                Text("No packages — check your connection, then Refresh.", style = Ca.type.footnote, color = Ca.colors.textTertiary)
            } else {
                for ((label, cat) in CATEGORIES) {
                    val group = packages.filter { it.category == cat }.sortedByDescending { it.path }
                    if (group.isEmpty()) continue
                    SectionHeader(label, small = true)
                    Card {
                        group.take(40).forEachIndexed { i, p ->
                            if (i > 0) Spacer(Modifier.height(6.dp))
                            PackageRow(p, enabled = !progress.busy && p.installable) {
                                scope.launch { status = backend.installSdkPackage(p.path); reload() }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CATEGORIES = listOf(
    "Platforms" to "PLATFORM",
    "Sources" to "SOURCES",
    "Build tools" to "BUILD_TOOLS",
    "Command-line tools" to "CMDLINE_TOOLS",
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
private fun PackageRow(p: UiSdkPackage, enabled: Boolean, onInstall: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.displayName, style = Ca.type.body, color = Ca.colors.textPrimary)
            Text(p.path + (if (p.sizeBytes > 0) "  ·  ${p.sizeBytes / 1_048_576} MB" else ""), style = Ca.type.caption, color = Ca.colors.textTertiary)
        }
        Spacer(Modifier.width(8.dp))
        if (p.installed) Text("Installed", style = Ca.type.footnote, color = Ca.colors.run)
        else ActionPill("Install", enabled = enabled, onClick = onInstall)
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
