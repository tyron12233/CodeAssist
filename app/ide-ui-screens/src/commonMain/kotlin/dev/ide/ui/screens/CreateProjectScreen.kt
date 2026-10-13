@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.ide.ui.generated.resources.templates_all
import dev.ide.ui.generated.resources.templates_no_match
import dev.ide.ui.generated.resources.templates_search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.toggle_off
import dev.ide.ui.generated.resources.toggle_on
import dev.ide.ui.generated.resources.choose_start_point
import dev.ide.ui.generated.resources.create_project
import dev.ide.ui.generated.resources.creating
import dev.ide.ui.generated.resources.new_project
import dev.ide.ui.generated.resources.no_templates_available
import dev.ide.ui.generated.resources.package_name
import dev.ide.ui.generated.resources.project_name
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.theme.resolveTint
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The Create-Project flow: a template gallery ([TemplateGallery], from [IdeBackend.projectTemplates]) followed
 * by a configure step whose form is driven entirely by the chosen template's [UiTemplateParam]s, so a new
 * template contributes its own fields with no UI change.
 * On success the backend swaps the active project and [onCreated] fires; [onCancel] backs out.
 */
@Composable
fun CreateProjectScreen(
    backend: IdeBackend,
    onCancel: () -> Unit,
    onCreated: () -> Unit,
    /** Pre-select a template (e.g. opened from a Projects Store item) and jump straight to its configure step. */
    initialTemplateId: String? = null,
) {
    val templates = remember { backend.projects.projectTemplates() }
    // Sample projects (weather, notes, the Compose games…) belong to the Explore page only; the
    // Create-Project gallery lists starter templates. They keep the `sample-` id prefix the store
    // partitions on. A sample can still be created here when deep-linked from Explore, since
    // `initialTemplateId` resolves against the full list below.
    val galleryTemplates = remember(templates) { templates.filterNot { it.id.startsWith("sample-") } }
    var selected by remember(initialTemplateId) {
        mutableStateOf(initialTemplateId?.let { id -> templates.firstOrNull { it.id == id } })
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        val sel = selected
        if (sel == null) {
            TemplateGallery(
                templates = galleryTemplates,
                onBack = onCancel,
                onPick = { selected = it },
            )
        } else {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            ) {
                Configure(
                    backend = backend,
                    template = sel,
                    onBack = { selected = null },
                    onCreated = onCreated,
                )
            }
        }
    }
}

// ---- step 1: gallery ----

/**
 * The template gallery, built to keep working as plugins add templates: a search over name, description and
 * category, a category filter with counts, and an adaptive grid of equal-height cards.
 *
 * At [GalleryRailBreakpoint] and wider the categories are a rail beside the grid; narrower, a scrolling row
 * of chips under the search. "All" with no search shows the templates grouped under category headings;
 * anything narrower is a flat list of what matched. Categories are free-form strings from whoever
 * contributed the template, so they group ignoring case (`java` and `Java` are one).
 */
@Composable
internal fun TemplateGallery(templates: List<UiProjectTemplate>, onBack: () -> Unit, onPick: (UiProjectTemplate) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    // A category's key (lower-cased), or null for All.
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember(templates) { templateCategories(templates) }
    val q = query.trim()
    val shown = remember(templates, q, category) {
        templates.filter { t ->
            (category == null || categoryKey(t) == category) &&
                (q.isEmpty() || listOf(t.displayName, t.description, t.category).any { it.contains(q, ignoreCase = true) })
        }
    }
    val grouped = category == null && q.isEmpty()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= GalleryRailBreakpoint
        val gutter = if (wide) 32.dp else 16.dp
        // The side gutter is applied per part rather than to the column, so the phone's chip row can scroll
        // edge to edge (a chip cut off at the screen edge says "more this way"; one cut at the gutter looks clipped).
        val inset = Modifier.padding(horizontal = gutter)
        Column(Modifier.widthIn(max = GalleryMaxWidth).fillMaxSize().padding(top = if (wide) 32.dp else 16.dp)) {
            Row(inset, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) {
                    Header(title = stringResource(Res.string.new_project), subtitle = stringResource(Res.string.choose_start_point), onBack = onBack)
                }
                if (wide) {
                    SearchPill(query, { query = it }, stringResource(Res.string.templates_search), Modifier.width(320.dp))
                }
            }
            if (!wide) {
                Spacer(Modifier.height(14.dp))
                SearchPill(query, { query = it }, stringResource(Res.string.templates_search), inset.fillMaxWidth())
                if (categories.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    CategoryChips(categories, templates.size, category, gutter) { category = it }
                }
            }
            Spacer(Modifier.height(if (wide) 24.dp else 12.dp))
            Row(inset.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                if (wide && categories.size > 1) {
                    CategoryRail(categories, templates.size, category, Modifier.width(200.dp)) { category = it }
                }
                TemplateGrid(
                    templates = shown,
                    grouped = grouped,
                    categories = categories,
                    emptyText = when {
                        templates.isEmpty() -> stringResource(Res.string.no_templates_available)
                        else -> stringResource(Res.string.templates_no_match, q)
                    },
                    onPick = onPick,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** One category of the gallery: its display [label] (as the first template spelled it) and its templates. */
internal data class TemplateCategory(val key: String, val label: String, val templates: List<UiProjectTemplate>)

private fun categoryKey(t: UiProjectTemplate): String = t.category.trim().lowercase().ifEmpty { "other" }

/** The categories in the order their first template arrives, which is the order the backend ranks them in. */
internal fun templateCategories(templates: List<UiProjectTemplate>): List<TemplateCategory> =
    templates.groupBy(::categoryKey).map { (key, group) ->
        val label = group.first().category.trim().ifEmpty { "Other" }.replaceFirstChar { it.uppercaseChar() }
        TemplateCategory(key, label, group)
    }

@Composable
private fun TemplateGrid(
    templates: List<UiProjectTemplate>,
    grouped: Boolean,
    categories: List<TemplateCategory>,
    emptyText: String,
    onPick: (UiProjectTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (templates.isEmpty()) {
        Text(
            emptyText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.padding(top = 24.dp),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TemplateCardMinWidth),
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (grouped && categories.size > 1) {
            categories.forEachIndexed { i, cat ->
                item(key = "header:${cat.key}", span = { GridItemSpan(maxLineSpan) }) {
                    SectionLabel(cat.label, Modifier.padding(top = if (i == 0) 0.dp else 12.dp))
                }
                items(cat.templates, key = { it.id }) { t -> TemplateCard(t, onClick = { onPick(t) }) }
            }
        } else {
            items(templates, key = { it.id }) { t -> TemplateCard(t, onClick = { onPick(t) }) }
        }
    }
}

/** A template as a grid cell: glyph, one-line name, and a description held to two lines, so every card in a
 *  row is the same height whatever its text. Hover outlines it on desktop. */
@Composable
private fun TemplateCard(template: UiProjectTemplate, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .background(if (hovered) c.surfaceContainerHigh else c.surface)
            .border(1.dp, if (hovered) c.outline else c.outlineVariant, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TemplateGlyph(template.iconId)
        Column(Modifier.weight(1f)) {
            Text(
                template.displayName,
                color = c.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                template.description,
                color = c.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** The desktop category filter: All, then each category, each with its count. */
@Composable
private fun CategoryRail(
    categories: List<TemplateCategory>,
    total: Int,
    selected: String?,
    modifier: Modifier = Modifier,
    onSelect: (String?) -> Unit,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CategoryRailItem(stringResource(Res.string.templates_all), total, selected == null) { onSelect(null) }
        for (cat in categories) {
            CategoryRailItem(cat.label, cat.templates.size, selected == cat.key) { onSelect(cat.key) }
        }
    }
}

@Composable
private fun CategoryRailItem(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ca.radius.md))
            .background(
                when {
                    selected -> c.secondaryContainer
                    hovered -> c.surfaceContainerHigh
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) c.onSecondaryContainer else c.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(count.toString(), color = c.outline, style = MaterialTheme.typography.labelMedium)
    }
}

/** The phone category filter: one scrolling row of chips, All first. */
@Composable
private fun CategoryChips(
    categories: List<TemplateCategory>,
    total: Int,
    selected: String?,
    gutter: Dp,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("all") { Chip(stringResource(Res.string.templates_all), total, selected == null) { onSelect(null) } }
        items(categories, key = { it.key }) { cat ->
            Chip(cat.label, cat.templates.size, selected == cat.key) { onSelect(cat.key) }
        }
    }
}

/** Below this the categories collapse from a rail into a row of chips. */
private val GalleryRailBreakpoint = 840.dp

/** The gallery's widest: three cards a row beside the rail, before the cards would stretch past readable. */
private val GalleryMaxWidth = 1120.dp

/** The narrowest a card gets before the grid drops a column: one column on a phone, two or three on desktop. */
private val TemplateCardMinWidth = 260.dp

// ---- step 2: configure ----

@Composable
private fun ColumnScope.Configure(
    backend: IdeBackend,
    template: UiProjectTemplate,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val state = rememberCreateProjectFormState(backend, template)

    Header(title = template.displayName, subtitle = template.description, onBack = onBack)
    Spacer(Modifier.height(20.dp))
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FormField(stringResource(Res.string.project_name), state.name, "MyProject", onChange = state::updateName)
        FormField(stringResource(Res.string.package_name), state.packageName, "com.example.app", onChange = state::updatePackage)
        template.parameters.forEach { p ->
            ParamControl(p, value = state.paramValues[p.key] ?: defaultValue(p), onChange = { state.updateParam(p.key, it) })
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        text = stringResource(if (state.busy) Res.string.creating else Res.string.create_project),
        onClick = { state.create(onCreated) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ParamControl(param: UiTemplateParam, value: String, onChange: (String) -> Unit) {
    when (param) {
        is UiTemplateParam.Text -> FormField(param.label, value, param.placeholder, onChange)
        is UiTemplateParam.Choice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(param.label)
            // FlowRow wraps the chips onto further lines on a narrow (phone) screen instead of squishing
            // them into a single overflowing row.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                param.options.forEach { opt ->
                    Chip(opt.label, selected = value == opt.value, onClick = { onChange(opt.value) })
                }
            }
        }
        is UiTemplateParam.Toggle -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(param.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            val on = value.toBooleanStrictOrNull() ?: false
            Box(
                Modifier
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.pill))
                    .clickable { onChange((!on).toString()) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(if (on) stringResource(Res.string.toggle_on) else stringResource(Res.string.toggle_off), color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- shared bits ----

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(36.dp)
                .pressScale(interaction)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.sm))
                .clickable(interaction, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.chevronLeft, stringResource(Res.string.back), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FormField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = Chip(label, count = null, selected, onClick)

/** A pill choice; [count], when given, trails the label in a quieter tone (the gallery's category chips). */
@Composable
private fun Chip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(Ca.radius.pill))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
        if (count != null) Text(count.toString(), color = fg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
    }
}

/** Render a template's icon id through the shared [TreeIcons] registry (same icons as the file tree). */
@Composable
private fun TemplateGlyph(iconId: String) {
    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.md)), contentAlignment = Alignment.Center) {
        when (val ic = TreeIcons.resolve(iconId)) {
            is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(24.dp), tint = resolveTint(ic.tint))
            is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(24.dp), tint = resolveTint(ic.tint))
            is TreeIcon.Badge -> Text(ic.text, color = ic.color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun defaultName(template: UiProjectTemplate): String = when {
    template.id.contains("android-app") -> "My App"
    template.id.contains("android-library") -> "My Library"
    template.id.contains("console") -> "My App"
    template.id.contains("library") -> "My Library"
    template.id.endsWith("-app") -> "My App"
    else -> "My Project"
}

internal fun defaultValue(p: UiTemplateParam): String = when (p) {
    is UiTemplateParam.Text -> p.default
    is UiTemplateParam.Choice -> p.options.getOrNull(p.defaultIndex)?.value ?: p.options.firstOrNull()?.value ?: ""
    is UiTemplateParam.Toggle -> p.default.toString()
}

internal fun slug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
