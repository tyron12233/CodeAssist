package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_models_all
import dev.ide.agent.ui.generated.resources.chat_models_all_vendors
import dev.ide.agent.ui.generated.resources.chat_models_favorite
import dev.ide.agent.ui.generated.resources.chat_models_favorites
import dev.ide.agent.ui.generated.resources.chat_models_free
import dev.ide.agent.ui.generated.resources.chat_models_free_only
import dev.ide.agent.ui.generated.resources.chat_models_none
import dev.ide.agent.ui.generated.resources.chat_models_recent
import dev.ide.agent.ui.generated.resources.chat_models_search
import dev.ide.agent.ui.generated.resources.chat_models_sort_name
import dev.ide.agent.ui.generated.resources.chat_models_sort_vendor
import dev.ide.agent.ui.generated.resources.chat_models_title
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

internal enum class ModelSort { NAME, VENDOR }

/** What the picker is narrowed to. */
internal data class ModelFilter(
    val query: String = "",
    val vendor: String? = null,
    val freeOnly: Boolean = false,
    val sort: ModelSort = ModelSort.NAME,
)

/** The picker's sections. A model shows once: a favorite is not repeated under recents, nor either under [all]. */
internal data class ModelSections(
    val favorites: List<UiAgentModel>,
    val recents: List<UiAgentModel>,
    val all: List<UiAgentModel>,
) {
    val isEmpty: Boolean get() = favorites.isEmpty() && recents.isEmpty() && all.isEmpty()
}

/** The vendor half of an OpenRouter-style `vendor/model` id, or null for a bare id. */
internal fun modelVendor(id: String): String? = id.substringBefore('/', "").takeIf { it.isNotBlank() }

/** OpenRouter's $0 variants. */
internal fun isFreeModel(id: String): Boolean = id.endsWith(":free", ignoreCase = true)

/** The name without OpenRouter's "Vendor: " prefix, which would otherwise make a name sort a vendor sort. */
internal fun modelName(model: UiAgentModel): String {
    val name = model.displayName
    val colon = name.indexOf(": ")
    return if (modelVendor(model.id) != null && colon in 1..40) name.substring(colon + 2) else name
}

/**
 * Narrow [models] by [filter] and split them into sections. Every whitespace-separated term of the query must
 * appear in the id, the display name or the vendor, so "anthropic sonnet" finds Anthropic's Sonnet models.
 * Favorites and recents keep their own order (favorites by name, recents newest first); the rest is sorted.
 */
internal fun sectionModels(
    models: List<UiAgentModel>,
    filter: ModelFilter,
    favorites: Set<String>,
    recents: List<String>,
): ModelSections {
    val terms = filter.query.lowercase().split(' ', '\t').filter { it.isNotBlank() }
    val matching = models.filter { m ->
        (filter.vendor == null || modelVendor(m.id) == filter.vendor) &&
            (!filter.freeOnly || isFreeModel(m.id)) &&
            terms.all { t -> m.id.lowercase().contains(t) || m.displayName.lowercase().contains(t) }
    }
    val byName = Comparator<UiAgentModel> { a, b -> naturalCompare(modelName(a), modelName(b)) }.thenBy { it.id }
    val comparator = when (filter.sort) {
        ModelSort.NAME -> byName
        ModelSort.VENDOR -> compareBy<UiAgentModel> { modelVendor(it.id) ?: "" }.then(byName)
    }
    val byId = matching.associateBy { it.id }
    val favs = matching.filter { it.id in favorites }.sortedWith(comparator)
    val recent = recents.mapNotNull { byId[it] }.filter { it.id !in favorites }
    val shown = (favs + recent).mapTo(HashSet()) { it.id }
    return ModelSections(favs, recent, matching.filter { it.id !in shown }.sortedWith(comparator))
}

/**
 * Case-insensitive order that reads runs of digits as numbers, so "Model 2" sorts before "Model 10" and
 * "GPT-4.1" before "GPT-5".
 */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            val sj = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/**
 * The model picker: search, sort, a vendor filter and a free-only switch over the provider's live list, with
 * starred and recent models on top. OpenRouter lists hundreds of models; the controls that cannot narrow a given
 * list (one vendor, no free models) are not shown, so a short list stays a short list.
 */
@Composable
internal fun ModelPickerDialog(
    models: List<UiAgentModel>,
    current: String,
    favorites: Set<String>,
    recents: List<String>,
    onPick: (String) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val vendors = remember(models) { models.mapNotNull { modelVendor(it.id) }.distinct().sorted() }
    val hasFree = remember(models) { models.any { isFreeModel(it.id) } }
    var query by rememberSaveable { mutableStateOf("") }
    var vendor by rememberSaveable { mutableStateOf<String?>(null) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(if (vendors.size > 1) ModelSort.VENDOR else ModelSort.NAME) }
    var vendorMenu by remember { mutableStateOf(false) }
    val sections = remember(models, query, vendor, freeOnly, sort, favorites, recents) {
        sectionModels(models, ModelFilter(query, vendor, freeOnly, sort), favorites, recents)
    }
    val searchFocus = remember { FocusRequester() }
    // Searching is the point of a long list; a short one is quicker to tap, and a keyboard would cover it.
    if (models.size > LONG_LIST) LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

    CenteredDialog(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(Ca.radius.xl))
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.chat_models_title),
                    color = scheme.onSurface, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onDismiss, iconSize = 16, boxSize = 30)
            }
            SearchField(query, stringResource(Res.string.chat_models_search, models.size), searchFocus) { query = it }
            if (vendors.size > 1 || hasFree) {
                // Scrolls sideways rather than clipping when a translation makes the chips wider than the dialog.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vendors.size > 1) {
                        Box {
                            FilterChip(vendor ?: stringResource(Res.string.chat_models_all_vendors), selected = vendor != null, trailing = true) { vendorMenu = true }
                            CaDropdownMenu(expanded = vendorMenu, onDismissRequest = { vendorMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.chat_models_all_vendors), style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { vendor = null; vendorMenu = false },
                                )
                                vendors.forEach { v ->
                                    val count = models.count { modelVendor(it.id) == v }
                                    DropdownMenuItem(
                                        text = { Text("$v  ($count)", style = MaterialTheme.typography.bodyMedium) },
                                        onClick = { vendor = v; vendorMenu = false },
                                    )
                                }
                            }
                        }
                        FilterChip(
                            stringResource(if (sort == ModelSort.VENDOR) Res.string.chat_models_sort_vendor else Res.string.chat_models_sort_name),
                            selected = false,
                        ) { sort = if (sort == ModelSort.VENDOR) ModelSort.NAME else ModelSort.VENDOR }
                    }
                    if (hasFree) FilterChip(stringResource(Res.string.chat_models_free_only), selected = freeOnly) { freeOnly = !freeOnly }
                }
            }
            if (sections.isEmpty) {
                Text(stringResource(Res.string.chat_models_none), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                val favoritesTitle = Res.string.chat_models_favorites
                listOf(
                    favoritesTitle to sections.favorites,
                    Res.string.chat_models_recent to sections.recents,
                    Res.string.chat_models_all to sections.all,
                ).forEach { (title, list) ->
                    if (list.isEmpty()) return@forEach
                    // A heading is noise when everything is in one section.
                    if (list.size != sections.favorites.size + sections.recents.size + sections.all.size) {
                        item(key = "h:$title") { SectionHeading(stringResource(title)) }
                    }
                    items(list, key = { "${title.key}:${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = model.id == current,
                            favorite = model.id in favorites,
                            onPick = { onPick(model.id); onDismiss() },
                            onToggleFavorite = { onToggleFavorite(model.id, model.id !in favorites) },
                        )
                    }
                }
            }
        }
    }
}

private const val LONG_LIST = 20

@Composable
private fun SearchField(value: String, placeholder: String, focus: FocusRequester, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(scheme.surfaceContainerHighest)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(CaIcons.close, null, Modifier.size(16.dp).clip(RoundedCornerShape(Ca.radius.pill)).clickable { onChange("") }, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, trailing: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.pill)
    Row(
        Modifier.clip(shape)
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHighest)
            .border(1.dp, if (selected) scheme.secondaryContainer else scheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val content = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
        if (selected) Icon(CaIcons.check, null, Modifier.size(14.dp), tint = content)
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        if (trailing) Icon(CaIcons.chevronDown, null, Modifier.size(12.dp), tint = content)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelRow(model: UiAgentModel, selected: Boolean, favorite: Boolean, onPick: () -> Unit, onToggleFavorite: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.md)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onPick)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val onRow = if (selected) scheme.onSecondaryContainer else scheme.onSurface
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    modelName(model), color = onRow, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (isFreeModel(model.id)) Chip(stringResource(Res.string.chat_models_free), fill = scheme.tertiaryContainer, textColor = scheme.onTertiaryContainer)
            }
            if (model.id != model.displayName) {
                Text(
                    model.id, color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) Icon(CaIcons.check, null, Modifier.size(16.dp), tint = onRow)
        IconButtonCa(
            if (favorite) CaIcons.starFilled else CaIcons.star, stringResource(Res.string.chat_models_favorite), onToggleFavorite,
            iconSize = 16, boxSize = 34,
            tint = if (favorite) scheme.primary else scheme.outline,
        )
    }
}
