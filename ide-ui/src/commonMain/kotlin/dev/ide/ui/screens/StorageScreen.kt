package dev.ide.ui.screens

import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStorageCategory
import dev.ide.ui.backend.UiStorageProject
import dev.ide.ui.backend.UiStorageReport
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.delete
import dev.ide.ui.generated.resources.delete_project
import dev.ide.ui.generated.resources.delete_project_content
import dev.ide.ui.generated.resources.settings_storage
import dev.ide.ui.generated.resources.storage_cat_build_desc
import dev.ide.ui.generated.resources.storage_cat_build_title
import dev.ide.ui.generated.resources.storage_cat_dependencies_desc
import dev.ide.ui.generated.resources.storage_cat_dependencies_title
import dev.ide.ui.generated.resources.storage_cat_index_desc
import dev.ide.ui.generated.resources.storage_cat_index_title
import dev.ide.ui.generated.resources.storage_cat_language_desc
import dev.ide.ui.generated.resources.storage_cat_language_title
import dev.ide.ui.generated.resources.storage_cat_other_desc
import dev.ide.ui.generated.resources.storage_cat_other_title
import dev.ide.ui.generated.resources.storage_cat_preview_desc
import dev.ide.ui.generated.resources.storage_cat_preview_title
import dev.ide.ui.generated.resources.storage_cat_projects_desc
import dev.ide.ui.generated.resources.storage_cat_projects_title
import dev.ide.ui.generated.resources.storage_cat_sdk_desc
import dev.ide.ui.generated.resources.storage_cat_sdk_title
import dev.ide.ui.generated.resources.storage_clear
import dev.ide.ui.generated.resources.storage_clear_all_caches
import dev.ide.ui.generated.resources.storage_clear_sdk_body
import dev.ide.ui.generated.resources.storage_clear_sdk_title
import dev.ide.ui.generated.resources.storage_freed
import dev.ide.ui.generated.resources.storage_open_badge
import dev.ide.ui.generated.resources.storage_projects_header
import dev.ide.ui.generated.resources.storage_total_used
import dev.ide.ui.generated.resources.storage_unavailable
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * The Storage screen: a horizontal stacked-bar graph of what's using disk under the app storage root, a
 * per-category legend with a Clear action for the regenerable ones, and a per-project delete list. Reached
 * from the Settings & Tools hub. Talks only to [IdeBackend.projects] ([dev.ide.ui.backend.ProjectService]):
 * [dev.ide.ui.backend.ProjectService.storageReport] to size, [clearStorageCategory][dev.ide.ui.backend.ProjectService.clearStorageCategory]
 * and [deleteProject][dev.ide.ui.backend.ProjectService.deleteProject] to reclaim; it recomputes after each
 * action. Clearing caches is silent; the destructive SDK clear and project deletes go through a confirm.
 */
@Composable
fun StorageScreen(backend: IdeBackend, onBack: () -> Unit) {
    var report by remember { mutableStateOf<UiStorageReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Destructive confirmations, held until the user commits.
    var pendingSdk by remember { mutableStateOf<UiStorageCategory?>(null) }
    var pendingProject by remember { mutableStateOf<UiStorageProject?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true
        report = backend.projects.storageReport()
        loading = false
    }

    val freedTemplate = stringResource(Res.string.storage_freed)
    // Clear a category, show how much was freed, then recompute.
    fun clear(cat: UiStorageCategory) {
        if (busy) return
        scope.launch {
            busy = true
            val before = cat.bytes
            backend.projects.clearStorageCategory(cat.id)
            toast = freedTemplate.replace("%1\$s", formatBytes(before))
            refreshKey++
            busy = false
        }
    }

    fun clearAllCaches() {
        val r = report ?: return
        if (busy) return
        scope.launch {
            busy = true
            var freed = 0L
            // The non-destructive clearable caches (everything except the SDK).
            r.categories.filter { it.clearable && !it.destructive }.forEach { c ->
                freed += c.bytes
                backend.projects.clearStorageCategory(c.id)
            }
            toast = freedTemplate.replace("%1\$s", formatBytes(freed))
            refreshKey++
            busy = false
        }
    }

    fun deleteProject(p: UiStorageProject) {
        if (busy) return
        scope.launch {
            busy = true
            backend.projects.deleteProject(p.rootPath)
            refreshKey++
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            StorageHeader(onBack)
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

            val r = report
            when {
                loading && r == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                }
                r == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(stringResource(Res.string.storage_unavailable), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge)
                }
                else -> StorageContent(
                    report = r,
                    busy = busy,
                    onClear = ::clear,
                    onClearAllCaches = ::clearAllCaches,
                    onRequestSdkClear = { pendingSdk = it },
                    onRequestDeleteProject = { pendingProject = it },
                )
            }
        }

        StorageToast(toast, Modifier.align(Alignment.BottomCenter)) { toast = null }
    }

    // ---- confirmations ----
    val sdk = pendingSdk
    ConfirmDialog(
        visible = sdk != null,
        title = stringResource(Res.string.storage_clear_sdk_title),
        body = stringResource(Res.string.storage_clear_sdk_body, sdk?.let { formatBytes(it.bytes) } ?: ""),
        confirmLabel = stringResource(Res.string.storage_clear),
        onCancel = { pendingSdk = null },
        onConfirm = { pendingSdk = null; sdk?.let(::clear) },
    )
    val proj = pendingProject
    ConfirmDialog(
        visible = proj != null,
        title = stringResource(Res.string.delete_project),
        body = stringResource(Res.string.delete_project_content, proj?.name ?: ""),
        confirmLabel = stringResource(Res.string.delete),
        onCancel = { pendingProject = null },
        onConfirm = { pendingProject = null; proj?.let(::deleteProject) },
    )
}

// ---- content ---------------------------------------------------------------------------------------

@Composable
private fun StorageContent(
    report: UiStorageReport,
    busy: Boolean,
    onClear: (UiStorageCategory) -> Unit,
    onClearAllCaches: () -> Unit,
    onRequestSdkClear: (UiStorageCategory) -> Unit,
    onRequestDeleteProject: (UiStorageProject) -> Unit,
) {
    val shown = report.categories.filter { it.bytes > 0 }
    val hasClearableCache = report.categories.any { it.clearable && !it.destructive && it.bytes > 0 }
    LazyColumn(
        Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("total") { TotalCard(report) }
        item("bar") { UsageBar(shown) }
        item("legend") {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg)).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                shown.forEach { c ->
                    CategoryRow(c, busy) { cat ->
                        if (cat.destructive) onRequestSdkClear(cat) else onClear(cat)
                    }
                }
            }
        }
        if (hasClearableCache) {
            item("clearAll") {
                WideButton(stringResource(Res.string.storage_clear_all_caches), enabled = !busy, onClick = onClearAllCaches)
            }
        }
        if (report.projects.isNotEmpty()) {
            item("projectsHeader") {
                Text(
                    stringResource(Res.string.storage_projects_header),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            items(report.projects, key = { it.rootPath }) { p ->
                ProjectRow(p, isOpen = p.rootPath == report.openProjectRootPath, busy = busy) { onRequestDeleteProject(p) }
            }
        }
    }
}

@Composable
private fun TotalCard(report: UiStorageReport) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(Res.string.storage_total_used), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Text(formatBytes(report.totalBytes), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            report.storageRootPath, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The horizontal stacked bar: one seamless segment per non-zero category, weighted by size. */
@Composable
private fun UsageBar(categories: List<UiStorageCategory>) {
    val shape = RoundedCornerShape(Ca.radius.pill)
    Box(Modifier.fillMaxWidth().height(16.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (categories.isNotEmpty()) {
            Row(Modifier.fillMaxSize()) {
                categories.forEach { c ->
                    Box(Modifier.weight(c.bytes.toFloat()).fillMaxHeight().background(storageColor(c.colorId)))
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: UiStorageCategory, busy: Boolean, onClear: (UiStorageCategory) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(storageColor(c.colorId)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(categoryTitle(c.id), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(categoryDescription(c.id), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(formatBytes(c.bytes), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (c.clearable) {
            ClearPill(stringResource(Res.string.storage_clear), destructive = c.destructive, enabled = !busy) { onClear(c) }
        }
    }
}

@Composable
private fun ProjectRow(p: UiStorageProject, isOpen: Boolean, busy: Boolean, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.md)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (p.isAndroid) CaIcons.pkg else CaIcons.folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(p.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatBytes(p.bytes), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        }
        if (isOpen) {
            Box(Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(stringResource(Res.string.storage_open_badge), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        } else {
            ClearPill(stringResource(Res.string.delete), destructive = true, enabled = !busy, onClick = onDelete)
        }
    }
}

// ---- small building blocks -------------------------------------------------------------------------

@Composable
private fun ClearPill(text: String, destructive: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val fg = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val bg = if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier
            .pressScale(interaction)
            .background(bg, RoundedCornerShape(Ca.radius.pill))
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) fg else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WideButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth()
            .pressScale(interaction)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StorageHeader(onBack: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, stringResource(Res.string.back), onBack)
            Icon(CaIcons.layers, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(Res.string.settings_storage), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    CenteredDialog(visible = visible, onDismiss = onCancel) {
        Column(
            Modifier.widthIn(max = 380.dp).padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton(stringResource(Res.string.cancel), Modifier.weight(1f), destructive = false, onClick = onCancel)
                DialogButton(confirmLabel, Modifier.weight(1f), destructive = true, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, modifier: Modifier, destructive: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .then(
                if (destructive) Modifier.background(MaterialTheme.colorScheme.error, RoundedCornerShape(Ca.radius.control))
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control)),
            )
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (destructive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StorageToast(toast: String?, modifier: Modifier, onDone: () -> Unit) {
    if (toast == null) return
    LaunchedEffect(toast) { kotlinx.coroutines.delay(2200); onDone() }
    Box(modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(Ide.colors.glassThick, RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.check, null, Modifier.size(16.dp), tint = Ide.colors.run)
            Text(toast, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- id → color / label / formatting ---------------------------------------------------------------

@Composable
private fun storageColor(colorId: String): Color = when (colorId) {
    "accent" -> MaterialTheme.colorScheme.primary
    "accentStrong" -> MaterialTheme.colorScheme.primary
    "info" -> Ide.colors.info
    "run" -> Ide.colors.run
    "success" -> Ide.colors.success
    "warning" -> Ide.colors.warning
    "gitModified" -> Ide.colors.gitModified
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun categoryTitle(id: String): String = when (id) {
    "dependencies" -> stringResource(Res.string.storage_cat_dependencies_title)
    "index" -> stringResource(Res.string.storage_cat_index_title)
    "build" -> stringResource(Res.string.storage_cat_build_title)
    "preview" -> stringResource(Res.string.storage_cat_preview_title)
    "language" -> stringResource(Res.string.storage_cat_language_title)
    "sdk" -> stringResource(Res.string.storage_cat_sdk_title)
    "projects" -> stringResource(Res.string.storage_cat_projects_title)
    else -> stringResource(Res.string.storage_cat_other_title)
}

@Composable
private fun categoryDescription(id: String): String = when (id) {
    "dependencies" -> stringResource(Res.string.storage_cat_dependencies_desc)
    "index" -> stringResource(Res.string.storage_cat_index_desc)
    "build" -> stringResource(Res.string.storage_cat_build_desc)
    "preview" -> stringResource(Res.string.storage_cat_preview_desc)
    "language" -> stringResource(Res.string.storage_cat_language_desc)
    "sdk" -> stringResource(Res.string.storage_cat_sdk_desc)
    "projects" -> stringResource(Res.string.storage_cat_projects_desc)
    else -> stringResource(Res.string.storage_cat_other_desc)
}

/** Human-readable byte size (B / KB / MB / GB), one decimal, no platform formatter (commonMain-safe). */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${round1(kb)} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${round1(mb)} MB"
    return "${round1(mb / 1024.0)} GB"
}

private fun round1(v: Double): String {
    val scaled = (v * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}
