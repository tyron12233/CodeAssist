@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import dev.ide.ui.components.BottomSheet
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.darken
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.icons.resolveTint
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Projects Store: the home screen's Store tab. A featured carousel, category filters, and section lists
 * over the catalog the host serves through [IdeBackend.store] (the bundled templates + sample projects today;
 * a remote, submission-backed catalog later). A search query or a category chip switches the body to a flat
 * results list. Tapping an item opens a detail sheet whose CTA creates from the template ([onCreateFromTemplate])
 * or installs a sample/community project.
 */
@Composable
fun ProjectsStoreScreen(
    backend: IdeBackend,
    onCreateFromTemplate: (templateId: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHub: (() -> Unit)? = null,
) {
    val available = remember { backend.store.storeAvailable() }
    if (!available) {
        dev.ide.ui.components.ComingSoon(
            icon = CaIcons.grid,
            title = "Store unavailable",
            description = "The project store isn't available in this build.",
            modifier = modifier.fillMaxSize().background(Ca.colors.bg),
        )
        return
    }

    val catalog by produceState(UiStoreCatalog(), backend) {
        value = runCatching { backend.store.catalog() }.getOrDefault(UiStoreCatalog())
    }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<UiStoreItem>>(emptyList()) }
    var selected by remember { mutableStateOf<UiStoreItem?>(null) }
    val filtering = query.isNotBlank() || category != null

    LaunchedEffect(query, category, filtering) {
        if (!filtering) { results = emptyList(); return@LaunchedEffect }
        delay(180)
        results = runCatching { backend.store.search(query, category) }.getOrDefault(emptyList())
    }

    Box(modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            StoreHeader(onOpenHub)
            SearchField(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = HPAD, vertical = 8.dp))
            CategoryRow(catalog.categories, selected = category, onSelect = { category = it })
            Spacer(Modifier.height(4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (filtering) {
                    ResultsList(results, onClick = { selected = it })
                } else {
                    Landing(catalog, onClick = { selected = it })
                }
            }
        }
        StoreDetailSheet(
            item = selected,
            onDismiss = { selected = null },
            onCreateFromTemplate = { id -> selected = null; onCreateFromTemplate(id) },
            backend = backend,
        )
    }
}

private val HPAD = 20.dp

// ---- header + search + categories ----

@Composable
private fun StoreHeader(onOpenHub: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = HPAD, end = HPAD, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Store", color = Ca.colors.textPrimary, style = Ca.type.large, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Templates and sample projects to start from",
                color = Ca.colors.textSecondary, style = Ca.type.subhead,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onOpenHub != null) IconButtonCa(CaIcons.gear, "Settings & tools", onOpenHub)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(18.dp), tint = Ca.colors.textTertiary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search the store", color = Ca.colors.textTertiary, style = Ca.type.subhead)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.size(22.dp).clickable(interaction, indication = null) { onChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.close, "Clear", Modifier.size(15.dp), tint = Ca.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun CategoryRow(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = HPAD, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip("All", selected == null) { onSelect(null) }
        categories.forEach { c -> FilterChip(c, selected == c) { onSelect(c) } }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction)
            .background(if (active) Ca.colors.accentSoft else Ca.colors.surface2, RoundedCornerShape(Ca.radius.pill))
            .border(1.dp, if (active) Ca.colors.accent.copy(alpha = 0.4f) else Ca.colors.hairline, RoundedCornerShape(Ca.radius.pill))
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (active) Ca.colors.accent else Ca.colors.textSecondary,
            style = Ca.type.footnote,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// ---- landing (featured + sections) ----

@Composable
private fun Landing(catalog: UiStoreCatalog, onClick: (UiStoreItem) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (catalog.featured.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FeaturedCarousel(catalog.featured, onClick)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = HPAD, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            catalog.sections.forEach { section -> StoreSection(section, onClick) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeaturedCarousel(items: List<UiStoreItem>, onClick: (UiStoreItem) -> Unit) {
    val pager = rememberPagerState(pageCount = { items.size })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(
            state = pager,
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = HPAD),
        ) { i ->
            FeaturedCard(items[i], onClick = { onClick(items[i]) })
        }
        if (items.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(items.size) { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier
                                .size(if (on) 8.dp else 6.dp)
                                .background(
                                    if (on) Ca.colors.accent else Ca.colors.textTertiary.copy(alpha = 0.4f),
                                    RoundedCornerShape(Ca.radius.pill),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(item: UiStoreItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val base = item.accentColor?.let { Color(it) } ?: Ca.colors.accent
    val shape = RoundedCornerShape(Ca.radius.xl)
    Box(
        Modifier
            .fillMaxWidth()
            .height(164.dp)
            .pressScale(interaction)
            .background(Brush.linearGradient(listOf(base, base.darken(0.55f))), shape)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        // A large translucent glyph bleeding off the right edge as decorative art.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)) {
            StoreGlyph(item.iconId, tile = 64.dp, glyph = 34.dp, fill = Color.White.copy(alpha = 0.18f), tint = Color.White)
        }
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeaturedKindChip(item)
            Spacer(Modifier.weight(1f))
            Text(item.title, color = Color.White, style = Ca.type.title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.summary,
                color = Color.White.copy(alpha = 0.88f),
                style = Ca.type.footnote,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.82f),
            )
        }
    }
}

@Composable
private fun FeaturedKindChip(item: UiStoreItem) {
    Box(
        Modifier
            .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(kindLabel(item).uppercase(), color = Color.White, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StoreSection(section: UiStoreSection, onClick: (UiStoreItem) -> Unit) {
    // A section with no items only renders when it has a story to tell (Community's coming-soon state).
    if (section.items.isEmpty() && section.id != "community") return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(section.title, color = Ca.colors.textPrimary, style = Ca.type.title3)
            section.subtitle?.let { Text(it, color = Ca.colors.textTertiary, style = Ca.type.footnote) }
        }
        if (section.items.isEmpty()) {
            EmptySectionCard()
        } else {
            section.items.forEachIndexed { i, item ->
                StoreRow(item, delayMillis = i * 40, onClick = { onClick(item) })
            }
        }
    }
}

@Composable
private fun EmptySectionCard() {
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(Ca.colors.accentSoft, RoundedCornerShape(Ca.radius.md)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.discord, null, Modifier.size(20.dp), tint = Ca.colors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text("Coming soon", color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
            Text(
                "Community projects will land here once submissions open.",
                color = Ca.colors.textSecondary, style = Ca.type.footnote,
            )
        }
    }
}

// ---- item rows + results ----

@Composable
private fun ResultsList(results: List<UiStoreItem>, onClick: (UiStoreItem) -> Unit) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(HPAD), contentAlignment = Alignment.TopCenter) {
            Text("No matches", color = Ca.colors.textTertiary, style = Ca.type.subhead, modifier = Modifier.padding(top = 24.dp))
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = HPAD, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        results.forEachIndexed { i, item -> StoreRow(item, delayMillis = i * 30, onClick = { onClick(item) }) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StoreRow(item: UiStoreItem, delayMillis: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    Row(
        Modifier
            .entranceSlideUp(delayMillis)
            .fillMaxWidth()
            .pressScale(interaction)
            .background(Ca.colors.surface, shape)
            .border(1.dp, Ca.colors.separator, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StoreGlyph(item.iconId, tile = 48.dp, glyph = 26.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.summary, color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(item.category, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
                if (!item.available) Chip("Soon", fill = Ca.colors.warning.copy(alpha = 0.16f), textColor = Ca.colors.warning)
                item.author?.let { Text("by $it", color = Ca.colors.textTertiary, style = Ca.type.caption2) }
            }
        }
        Icon(CaIcons.chevronRight, null, Modifier.size(20.dp), tint = Ca.colors.textTertiary)
    }
}

// ---- detail sheet ----

@Composable
private fun StoreDetailSheet(
    item: UiStoreItem?,
    onDismiss: () -> Unit,
    onCreateFromTemplate: (String) -> Unit,
    backend: IdeBackend,
) {
    BottomSheet(visible = item != null, onDismiss = onDismiss, heightFraction = 0.6f) {
        if (item == null) return@BottomSheet
        val scope = rememberCoroutineScope()
        var message by remember(item.id) { mutableStateOf<String?>(null) }
        var busy by remember(item.id) { mutableStateOf(false) }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StoreGlyph(item.iconId, tile = 56.dp, glyph = 30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, color = Ca.colors.textPrimary, style = Ca.type.title3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.category, color = Ca.colors.textSecondary, style = Ca.type.footnote)
                        item.author?.let {
                            Text("·", color = Ca.colors.textTertiary, style = Ca.type.footnote)
                            Text(it, color = Ca.colors.textTertiary, style = Ca.type.footnote)
                        }
                    }
                }
            }
            Text(item.description, color = Ca.colors.textSecondary, style = Ca.type.subhead)
            if (item.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.tags.forEach { Chip(it, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary) }
                }
            }
            message?.let { Text(it, color = Ca.colors.textTertiary, style = Ca.type.footnote) }
            Spacer(Modifier.weight(1f))
            when {
                item.kind == UiStoreItemKind.Template && item.templateId != null ->
                    PrimaryButton(
                        "Use this template",
                        onClick = { onCreateFromTemplate(item.templateId!!) },
                        icon = CaIcons.plus,
                        modifier = Modifier.fillMaxWidth(),
                    )
                item.available ->
                    PrimaryButton(
                        if (busy) "Installing…" else "Get",
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val r = backend.store.install(item.id)
                                    busy = false
                                    message = r.message
                                }
                            }
                        },
                        icon = CaIcons.download,
                        modifier = Modifier.fillMaxWidth(),
                    )
                else -> DisabledCta("Coming soon")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DisabledCta(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(Ca.colors.surface3, RoundedCornerShape(Ca.radius.control)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ca.colors.textTertiary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

// ---- shared bits ----

/** Render a store item's icon id through the shared [TreeIcons] registry, on a soft rounded tile. */
@Composable
private fun StoreGlyph(
    iconId: String,
    tile: androidx.compose.ui.unit.Dp,
    glyph: androidx.compose.ui.unit.Dp,
    fill: Color = Ca.colors.surface2,
    tint: Color? = null,
) {
    Box(Modifier.size(tile).background(fill, RoundedCornerShape(Ca.radius.md)), contentAlignment = Alignment.Center) {
        when (val ic = TreeIcons.resolve(iconId)) {
            is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(glyph), tint = tint ?: resolveTint(ic.tint))
            is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(glyph), tint = tint ?: resolveTint(ic.tint))
            is TreeIcon.Badge -> Text(ic.text, color = tint ?: ic.color, style = Ca.type.title3, fontWeight = FontWeight.Bold)
        }
    }
}

private fun kindLabel(item: UiStoreItem): String = when (item.kind) {
    UiStoreItemKind.Template -> "Template"
    UiStoreItemKind.Sample -> "Sample"
    UiStoreItemKind.Community -> "Community"
}
