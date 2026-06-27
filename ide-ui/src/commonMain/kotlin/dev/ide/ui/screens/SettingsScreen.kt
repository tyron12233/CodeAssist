package dev.ide.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiInspection
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettingsPage
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.components.AdvancedGroup
import dev.ide.ui.components.CaSwitch
import dev.ide.ui.components.GlassMaterial
import dev.ide.ui.components.GlassSurface
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.SettingsActionRow
import dev.ide.ui.components.SettingsCard
import dev.ide.ui.components.SettingsCategoryItem
import dev.ide.ui.components.SettingsChoiceRow
import dev.ide.ui.components.SettingsSliderRow
import dev.ide.ui.components.SettingsTextRow
import dev.ide.ui.components.SettingsToggleRow
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WIDE_BREAKPOINT = 720.dp

/** Keys the screen handles with a UI flow rather than [IdeBackend.invokeSettingAction] (built-in Privacy). */
private const val ACTION_VIEW_LOGS = "viewLogs"
private const val ACTION_BACKUP = "backup"

/**
 * The Settings screen. Pages come from [IdeBackend.settingsPages] — built-in categories plus any a plugin
 * contributes — and every control is rendered generically from its descriptor, so a plugin's preferences
 * appear with no change here. Wide layouts get a category sidebar + content pane; narrow (phone) layouts
 * drill in from a category list. Talks only to [IdeBackend].
 *
 * Appearance/editor changes are applied live by the host re-reading [IdeBackend.settings] on [onSettingsChanged].
 */
@Composable
fun SettingsScreen(
    backend: IdeBackend,
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
    onOpenLogs: () -> Unit,
    codeFont: FontFamily = FontFamily.Monospace,
    fileActions: FileActions = FileActions.None,
) {
    val pages = remember { backend.settingsPages() }
    // Local mirror of each control's value (keyed "pageId.controlKey"), seeded from the descriptors. Controls
    // read/write this for instant feedback; each write also persists through the backend.
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            pages.forEach { page -> page.controls.forEach { c -> encodeValue(c)?.let { put("${page.id}.${c.key}", it) } } }
        }
    }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    val onSet: (String, String, String) -> Unit = { pageId, key, encoded ->
        values["$pageId.$key"] = encoded
        backend.setSetting(pageId, key, encoded)
        onSettingsChanged()
    }
    val onAction: (String, UiSettingControl.Action) -> Unit = { pageId, action ->
        when (action.key) {
            ACTION_VIEW_LOGS -> onOpenLogs()
            ACTION_BACKUP -> scope.launch { backend.backupProjects()?.let { fileActions.share(it) }; toast = "Backup ready" }
            else -> scope.launch { backend.invokeSettingAction(pageId, action.key)?.let { toast = it } }
        }
    }

    Box(Modifier.fillMaxSize().background(Ca.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            SettingsHeader(onBack)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No settings available.", color = Ca.colors.textTertiary, style = Ca.type.subhead)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= WIDE_BREAKPOINT) {
                        WideLayout(backend, pages, values, codeFont, onSet, onAction)
                    } else {
                        NarrowLayout(backend, pages, values, codeFont, onSet, onAction)
                    }
                }
            }
        }
        ToastBar(toast, Modifier.align(Alignment.BottomCenter))
    }
}

// ---- layouts -------------------------------------------------------------------------------------

@Composable
private fun WideLayout(
    backend: IdeBackend,
    pages: List<UiSettingsPage>,
    values: MutableMap<String, String>,
    codeFont: FontFamily,
    onSet: (String, String, String) -> Unit,
    onAction: (String, UiSettingControl.Action) -> Unit,
) {
    var selectedId by remember { mutableStateOf(pages.first().id) }
    val selected = pages.firstOrNull { it.id == selectedId } ?: pages.first()
    Row(Modifier.fillMaxSize()) {
        // Sidebar
        Column(
            Modifier.width(248.dp).fillMaxHeight().background(Ca.colors.surface.copy(alpha = 0.4f))
                .verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pages.forEach { page ->
                SettingsCategoryItem(page.title, iconFor(page.iconId), page.id == selectedId, showChevron = false) { selectedId = page.id }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ca.colors.separator))
        // Content
        Crossfade(targetState = selected.id, animationSpec = tween(Motion.FAST), label = "settingsPane", modifier = Modifier.weight(1f).fillMaxHeight()) { id ->
            val page = pages.firstOrNull { it.id == id } ?: return@Crossfade
            PageContent(backend, page, values, codeFont, onSet, onAction)
        }
    }
}

@Composable
private fun NarrowLayout(
    backend: IdeBackend,
    pages: List<UiSettingsPage>,
    values: MutableMap<String, String>,
    codeFont: FontFamily,
    onSet: (String, String, String) -> Unit,
    onAction: (String, UiSettingControl.Action) -> Unit,
) {
    var openId by remember { mutableStateOf<String?>(null) }
    val open = openId?.let { id -> pages.firstOrNull { it.id == id } }
    if (open == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pages, key = { it.id }) { page ->
                SettingsCategoryItem(page.title, iconFor(page.iconId), selected = false, showChevron = true) { openId = page.id }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButtonCa(CaIcons.chevronLeft, "All settings", { openId = null })
                Text(open.title, color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold)
            }
            PageContent(backend, open, values, codeFont, onSet, onAction, Modifier.weight(1f))
        }
    }
}

// ---- one page ------------------------------------------------------------------------------------

@Composable
private fun PageContent(
    backend: IdeBackend,
    page: UiSettingsPage,
    values: MutableMap<String, String>,
    codeFont: FontFamily,
    onSet: (String, String, String) -> Unit,
    onAction: (String, UiSettingControl.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val regular = page.controls.filter { !it.advanced }
    val advanced = page.controls.filter { it.advanced }
    // Regular controls grouped by their optional sub-heading (null group first, in declaration order).
    val groups = regular.groupBy { it.group }.toList()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { (groupTitle, controls) ->
            item(groupTitle ?: "_") {
                SettingsCard(groupTitle) {
                    controls.forEach { c -> ControlRow(page.id, c, values, codeFont, onSet, onAction) }
                    if (advanced.isNotEmpty() && groupTitle == groups.first().first) {
                        AdvancedGroup { advanced.forEach { c -> ControlRow(page.id, c, values, codeFont, onSet, onAction) } }
                    }
                }
            }
        }
        // A page with ONLY advanced controls still surfaces them.
        if (regular.isEmpty() && advanced.isNotEmpty()) {
            item("advancedOnly") { SettingsCard(null) { AdvancedGroup { advanced.forEach { c -> ControlRow(page.id, c, values, codeFont, onSet, onAction) } } } }
        }
        if (page.inspectionsSection) {
            item("inspections") { InspectionsCard(backend) }
        }
    }
}

@Composable
private fun ControlRow(
    pageId: String,
    c: UiSettingControl,
    values: MutableMap<String, String>,
    codeFont: FontFamily,
    onSet: (String, String, String) -> Unit,
    onAction: (String, UiSettingControl.Action) -> Unit,
) {
    val stored = values["$pageId.${c.key}"]
    when (c) {
        is UiSettingControl.Toggle -> SettingsToggleRow(c.title, c.description, stored?.toBooleanStrictOrNull() ?: c.value) {
            onSet(pageId, c.key, it.toString())
        }
        is UiSettingControl.Slider -> SettingsSliderRow(c.title, c.description, stored?.toIntOrNull() ?: c.value, c.min, c.max, c.step, c.unit) {
            onSet(pageId, c.key, it.toString())
        }
        is UiSettingControl.Choice -> SettingsChoiceRow(c.title, c.description, stored ?: c.value, c.options.map { it.value to it.label }) {
            onSet(pageId, c.key, it)
        }
        is UiSettingControl.Text -> SettingsTextRow(c.title, c.description, stored ?: c.value, c.placeholder, codeFont) {
            onSet(pageId, c.key, it)
        }
        is UiSettingControl.Action -> SettingsActionRow(c.title, c.description, c.buttonLabel, c.destructive) { onAction(pageId, c) }
    }
}

// ---- inspections list ----------------------------------------------------------------------------

@Composable
private fun InspectionsCard(backend: IdeBackend) {
    var inspections by remember { mutableStateOf(backend.inspections()) }
    if (inspections.isEmpty()) return
    val byLang = inspections.groupBy { it.language }.toList().sortedBy { it.first }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        byLang.forEach { (lang, list) ->
            SettingsCard("$lang inspections") {
                list.forEach { insp ->
                    InspectionRow(insp) { enabled, severity ->
                        backend.setInspection(insp.id, enabled, severity)
                        inspections = backend.inspections()
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionRow(insp: UiInspection, onChange: (Boolean, UiSeverity) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(insp.displayName, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(insp.tier, color = Ca.colors.textTertiary, style = Ca.type.caption2)
            }
            CaSwitch(insp.enabled) { onChange(it, insp.severity) }
        }
        if (insp.enabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UiSeverity.entries.forEach { sev ->
                    SeverityChip(sev, sev == insp.severity) { onChange(true, sev) }
                }
            }
        }
    }
}

@Composable
private fun SeverityChip(severity: UiSeverity, selected: Boolean, onClick: () -> Unit) {
    val color = when (severity) {
        UiSeverity.Error -> Ca.colors.error
        UiSeverity.Warning -> Ca.colors.warning
        UiSeverity.Info -> Ca.colors.info
        UiSeverity.Hint -> Ca.colors.textTertiary
    }
    Box(
        Modifier.background(if (selected) color.copy(alpha = 0.20f) else Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(severity.name, color = if (selected) color else Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.Medium)
    }
}

// ---- chrome --------------------------------------------------------------------------------------

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), GlassMaterial.Regular) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButtonCa(CaIcons.chevronLeft, "Back", onBack)
            Icon(CaIcons.gear, null, Modifier.size(20.dp), tint = Ca.colors.accent)
            Text("Settings", color = Ca.colors.textPrimary, style = Ca.type.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToastBar(toast: String?, modifier: Modifier) {
    if (toast == null) return
    Box(modifier.fillMaxWidth().padding(bottom = 28.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.pill))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.check, null, Modifier.size(16.dp), tint = Ca.colors.run)
            Text(toast, color = Ca.colors.textPrimary, style = Ca.type.footnote, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- helpers -------------------------------------------------------------------------------------

/** The encoded current value of a control for the local mirror (null for value-less actions). */
private fun encodeValue(c: UiSettingControl): String? = when (c) {
    is UiSettingControl.Toggle -> c.value.toString()
    is UiSettingControl.Slider -> c.value.toString()
    is UiSettingControl.Choice -> c.value
    is UiSettingControl.Text -> c.value
    is UiSettingControl.Action -> null
}

/** Map a page's icon id (resolved generically, so plugin icons work too) to a glyph; gear is the fallback. */
private fun iconFor(iconId: String): ImageVector = when (iconId) {
    "eye" -> CaIcons.eye
    "code" -> CaIcons.code
    "sparkle" -> CaIcons.sparkle
    "lightbulb" -> CaIcons.lightbulb
    "hammer" -> CaIcons.hammer
    "info" -> CaIcons.info
    "pkg" -> CaIcons.pkg
    "layers" -> CaIcons.layers
    "braces" -> CaIcons.braces
    "terminal" -> CaIcons.terminal
    "image" -> CaIcons.image
    "box" -> CaIcons.box
    else -> CaIcons.gear
}
