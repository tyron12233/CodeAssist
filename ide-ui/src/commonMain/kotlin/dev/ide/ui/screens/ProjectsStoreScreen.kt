package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreCatalog
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.backend.UiStoreSection
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.Chip
import dev.ide.ui.components.ComingSoon
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.darken
import dev.ide.ui.components.entranceSlideUp
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.clear
import dev.ide.ui.generated.resources.store_all
import dev.ide.ui.generated.resources.store_coming_soon
import dev.ide.ui.generated.resources.store_community_content
import dev.ide.ui.generated.resources.store_kind_community
import dev.ide.ui.generated.resources.store_kind_sample
import dev.ide.ui.generated.resources.store_kind_template
import dev.ide.ui.generated.resources.store_no_matches
import dev.ide.ui.generated.resources.store_search_hint
import dev.ide.ui.generated.resources.store_settings_and_tools
import dev.ide.ui.generated.resources.store_soon
import dev.ide.ui.generated.resources.store_subtitle
import dev.ide.ui.generated.resources.store_title
import dev.ide.ui.generated.resources.store_unavailable
import dev.ide.ui.generated.resources.store_unavailable_content
import dev.ide.ui.generated.resources.store_by_author
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.theme.resolveTint
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The Projects Store — a varied, single-scroll storefront (App-Store-style rather than a flat list): a hero
 * carousel, colorful category tiles, a horizontal shelf of starter templates, a two-column grid of sample
 * projects, and a community banner. Everything is one [LazyColumn] so the search bar scrolls with the content.
 * Data comes from [IdeBackend.store]; a search query or a category tile switches the body to a results grid.
 * Tapping an item calls [onOpenItem], which the host routes to the full-screen [StoreItemScreen].
 */
@Composable
fun ProjectsStoreScreen(
    backend: IdeBackend,
    onOpenItem: (UiStoreItem) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHub: (() -> Unit)? = null,
) {
    val available = remember { backend.store.storeAvailable() }
    if (!available) {
        ComingSoon(
            icon = CaIcons.grid,
            title = stringResource(Res.string.store_unavailable),
            description = stringResource(Res.string.store_unavailable_content),
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
    val filtering = query.isNotBlank() || category != null
    val adsActive = LocalAds.current?.adsActive == true

    LaunchedEffect(query, category, filtering) {
        if (!filtering) { results = emptyList(); return@LaunchedEffect }
        delay(180)
        results = runCatching { backend.store.search(query, category) }.getOrDefault(emptyList())
    }

    Box(modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 820.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "header") { StoreHeader(onOpenHub) }
            item(key = "search") {
                SearchField(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = HPAD, vertical = 8.dp))
            }
            item(key = "categories") {
                CategoryStrip(catalog.categories, selected = category, onSelect = { category = it })
            }

            if (filtering) {
                resultsGrid(results, onClick = onOpenItem)
            } else {
                if (catalog.featured.isNotEmpty()) {
                    item(key = "featured") {
                        Spacer(Modifier.height(6.dp))
                        FeaturedCarousel(catalog.featured, onClick = onOpenItem)
                    }
                }
                // A native ad reads as just another shelf item in the gallery — the most natural placement.
                if (adsActive) {
                    item(key = "ad") {
                        AdSlot(AdPlacement.STORE, Modifier.padding(horizontal = HPAD, vertical = 8.dp))
                    }
                }
                catalog.sections.forEach { section -> storeSection(section, onClick = onOpenItem) }
            }
        }
    }
}

private val HPAD = 20.dp

// ---- header + search ----

@Composable
private fun StoreHeader(onOpenHub: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = HPAD, end = HPAD, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.store_title), color = Ca.colors.textPrimary, style = Ca.type.large, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.store_subtitle),
                color = Ca.colors.textSecondary, style = Ca.type.subhead,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onOpenHub != null) IconButtonCa(CaIcons.gear, stringResource(Res.string.store_settings_and_tools), onOpenHub)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.control))
            .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.control))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(18.dp), tint = Ca.colors.textTertiary)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(stringResource(Res.string.store_search_hint), color = Ca.colors.textTertiary, style = Ca.type.subhead)
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
                Icon(CaIcons.close, stringResource(Res.string.clear), Modifier.size(15.dp), tint = Ca.colors.textTertiary)
            }
        }
    }
}

// ---- colorful category strip ----

@Composable
private fun CategoryStrip(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = HPAD, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CategoryTile(stringResource(Res.string.store_all), Ca.colors.accent, CaIcons.grid, selected == null) { onSelect(null) }
        categories.forEach { c ->
            CategoryTile(c, categoryColor(c), categoryIcon(c), selected == c) { onSelect(if (selected == c) null else c) }
        }
    }
}

@Composable
private fun CategoryTile(label: String, color: Color, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier
            .width(96.dp)
            .height(76.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(Brush.linearGradient(listOf(color, color.darken(0.55f))))
            .border(2.dp, if (active) Color.White.copy(alpha = 0.9f) else Color.Transparent, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
        Spacer(Modifier.weight(1f))
        Text(label, color = Color.White, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- featured hero carousel ----

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
    val base = item.accentColor?.let { Color(it) } ?: categoryColor(item.category)
    val shape = RoundedCornerShape(Ca.radius.xl)
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(Brush.linearGradient(listOf(base, base.darken(0.6f))))
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        // A large translucent glyph bleeding off the right edge as decorative art.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) {
            StoreGlyph(item.iconId, tile = 76.dp, glyph = 40.dp, fill = Color.White.copy(alpha = 0.16f), tint = Color.White)
        }
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillChip(kindLabel(item).uppercase())
            Spacer(Modifier.weight(1f))
            Text(item.title, color = Color.White, style = Ca.type.title2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.summary,
                color = Color.White.copy(alpha = 0.9f),
                style = Ca.type.footnote,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.82f),
            )
            if (item.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.take(2).forEach { PillChip(it) }
                }
            }
        }
    }
}

@Composable
private fun PillChip(text: String) {
    Box(
        Modifier.background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ---- section shelves (varied: horizontal carousel for templates, grid for samples, banner for community) ----

private fun androidx.compose.foundation.lazy.LazyListScope.storeSection(
    section: UiStoreSection,
    onClick: (UiStoreItem) -> Unit,
) {
    when {
        section.id == "community" -> {
            item(key = "sh-${section.id}") { SectionHeader(section) }
            item(key = "community-banner") { CommunityBanner(Modifier.padding(horizontal = HPAD, vertical = 4.dp)) }
        }
        section.items.isEmpty() -> Unit
        section.id == "samples" -> {
            item(key = "sh-${section.id}") { SectionHeader(section) }
            gridOf(section.items, keyPrefix = section.id, onClick = onClick)
        }
        else -> {
            // Templates (and any other shelf): a horizontal carousel of taller cards.
            item(key = "sh-${section.id}") { SectionHeader(section) }
            item(key = "row-${section.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = HPAD),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(section.items, key = { "c-${section.id}-${it.id}" }) { item ->
                        TemplateCard(item, onClick = { onClick(item) })
                    }
                }
            }
        }
    }
}

/** A two-column grid rendered as chunked rows (so it lives inside the outer [LazyColumn] without a nested grid). */
private fun androidx.compose.foundation.lazy.LazyListScope.gridOf(
    list: List<UiStoreItem>,
    keyPrefix: String,
    onClick: (UiStoreItem) -> Unit,
) {
    val rows = list.chunked(2)
    items(rows, key = { "g-$keyPrefix-${it.first().id}" }) { pair ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = HPAD, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            pair.forEach { item -> ItemTile(item, Modifier.weight(1f), onClick = { onClick(item) }) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultsGrid(
    results: List<UiStoreItem>,
    onClick: (UiStoreItem) -> Unit,
) {
    if (results.isEmpty()) {
        item(key = "no-matches") {
            Box(Modifier.fillMaxWidth().padding(HPAD), contentAlignment = Alignment.TopCenter) {
                Text(
                    stringResource(Res.string.store_no_matches),
                    color = Ca.colors.textTertiary, style = Ca.type.subhead,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    } else {
        item(key = "results-gap") { Spacer(Modifier.height(4.dp)) }
        gridOf(results, keyPrefix = "results", onClick = onClick)
    }
}

@Composable
private fun SectionHeader(section: UiStoreSection) {
    Column(
        Modifier.fillMaxWidth().padding(start = HPAD, end = HPAD, top = 22.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(section.title, color = Ca.colors.textPrimary, style = Ca.type.title3, modifier = Modifier.weight(1f, fill = false))
            if (section.items.isNotEmpty()) Chip(section.items.size.toString(), fill = Ca.colors.surface2, textColor = Ca.colors.textTertiary)
        }
        section.subtitle?.let { Text(it, color = Ca.colors.textTertiary, style = Ca.type.footnote) }
    }
}

// ---- card variants ----

/** A tall carousel card (starter templates): a colored header band with the glyph, then title + meta. */
@Composable
private fun TemplateCard(item: UiStoreItem, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val accent = item.accentColor?.let { Color(it) } ?: categoryColor(item.category)
    val shape = RoundedCornerShape(Ca.radius.lg)
    Column(
        Modifier
            .width(230.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(Ca.colors.surface)
            .border(1.dp, Ca.colors.separator, shape)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(92.dp).background(Brush.linearGradient(listOf(accent, accent.darken(0.55f)))),
        ) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                StoreGlyph(item.iconId, tile = 52.dp, glyph = 28.dp, fill = Color.White.copy(alpha = 0.16f), tint = Color.White)
            }
            Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { PillChip(kindLabel(item).uppercase()) }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.title, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.summary, color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(item.category, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
                if (item.installs >= 0) InstallStat(item.installs)
            }
        }
    }
}

/** A compact grid tile (samples / search results): a preview banner (when the sample ships one) or an
 *  accent-tinted glyph, then title, summary, and status chips. */
@Composable
private fun ItemTile(item: UiStoreItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val accent = item.accentColor?.let { Color(it) } ?: categoryColor(item.category)
    val shape = RoundedCornerShape(Ca.radius.lg)
    val showPreview = hasSamplePreview(item.previewKey)
    Column(
        modifier
            .entranceSlideUp()
            .pressScale(interaction)
            .clip(shape)
            .background(Ca.colors.surface)
            .border(1.dp, Ca.colors.separator, shape)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        if (showPreview) SamplePreview(item.previewKey!!, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!showPreview) {
                StoreGlyph(item.iconId, tile = 44.dp, glyph = 24.dp, fill = accent.copy(alpha = 0.16f), tint = accent)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title, color = Ca.colors.textPrimary, style = Ca.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.summary, color = Ca.colors.textSecondary, style = Ca.type.footnote, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!item.available) Chip(stringResource(Res.string.store_soon), fill = Ca.colors.warning.copy(alpha = 0.16f), textColor = Ca.colors.warning)
                else Chip(item.category, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary)
                if (item.installs >= 0) InstallStat(item.installs)
                item.author?.let { Text(stringResource(Res.string.store_by_author, it), color = Ca.colors.textTertiary, style = Ca.type.caption2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun InstallStat(installs: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(CaIcons.download, null, Modifier.size(12.dp), tint = Ca.colors.textTertiary)
        Text(compactCount(installs), color = Ca.colors.textTertiary, style = Ca.type.caption2)
    }
}

@Composable
private fun CommunityBanner(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Ca.radius.lg)
    val accent = Color(0xFF5865F2)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.08f))))
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(Ca.radius.md)).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CaIcons.discord, null, Modifier.size(24.dp), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.store_coming_soon), color = Ca.colors.textPrimary, style = Ca.type.headline)
            Text(stringResource(Res.string.store_community_content), color = Ca.colors.textSecondary, style = Ca.type.footnote)
        }
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
    Box(Modifier.size(tile).clip(RoundedCornerShape(Ca.radius.md)).background(fill), contentAlignment = Alignment.Center) {
        when (val ic = TreeIcons.resolve(iconId)) {
            is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(glyph), tint = tint ?: resolveTint(ic.tint))
            is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(glyph), tint = tint ?: resolveTint(ic.tint))
            is TreeIcon.Badge -> Text(ic.text, color = tint ?: ic.color, style = Ca.type.title3, fontWeight = FontWeight.Bold)
        }
    }
}

/** A short human count (1200 → "1.2k") for the soft install/star stat on a card. */
private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 100_000 / 10.0}m"
    n >= 1_000 -> "${n / 100 / 10.0}k"
    else -> n.toString()
}

/** A brand color for a category (known ones fixed; the rest hashed into a palette) — drives tiles + card accents. */
private fun categoryColor(category: String): Color = when (category.lowercase()) {
    "android" -> Color(0xFF3DDC84)
    "kotlin" -> Color(0xFF7F52FF)
    "java" -> Color(0xFFF89820)
    "games" -> Color(0xFFE0533D)
    "compose" -> Color(0xFF3FBDD9)
    "samples" -> Color(0xFF00A8A0)
    "community" -> Color(0xFF5865F2)
    "other" -> Color(0xFF8E8E93)
    else -> TILE_PALETTE[(category.hashCode() and 0x7fffffff) % TILE_PALETTE.size]
}

private val TILE_PALETTE = listOf(
    Color(0xFF3DDC84), Color(0xFF7F52FF), Color(0xFFF89820),
    Color(0xFFE0533D), Color(0xFF3FBDD9), Color(0xFFB487F7),
)

private fun categoryIcon(category: String): ImageVector = when (category.lowercase()) {
    "android" -> CaIcons.androidLogo
    "kotlin" -> CaIcons.code
    "java" -> CaIcons.braces
    "games" -> CaIcons.star
    "compose" -> CaIcons.sparkle
    "community" -> CaIcons.discord
    "samples" -> CaIcons.layers
    else -> CaIcons.grid
}

@Composable
private fun kindLabel(item: UiStoreItem): String = when (item.kind) {
    UiStoreItemKind.Template -> stringResource(Res.string.store_kind_template)
    UiStoreItemKind.Sample -> stringResource(Res.string.store_kind_sample)
    UiStoreItemKind.Community -> stringResource(Res.string.store_kind_community)
}
