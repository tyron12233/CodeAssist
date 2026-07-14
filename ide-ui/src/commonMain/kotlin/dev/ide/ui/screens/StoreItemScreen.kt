@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreItem
import dev.ide.ui.backend.UiStoreItemKind
import dev.ide.ui.components.Chip
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.darken
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.store_by_author
import dev.ide.ui.generated.resources.store_coming_soon
import dev.ide.ui.generated.resources.store_create
import dev.ide.ui.generated.resources.store_get
import dev.ide.ui.generated.resources.store_installing
import dev.ide.ui.generated.resources.store_item_about
import dev.ide.ui.generated.resources.store_item_details
import dev.ide.ui.generated.resources.store_item_downloads
import dev.ide.ui.generated.resources.store_item_language
import dev.ide.ui.generated.resources.store_item_publisher
import dev.ide.ui.generated.resources.store_item_type
import dev.ide.ui.generated.resources.store_item_whats_included
import dev.ide.ui.generated.resources.store_kind_community
import dev.ide.ui.generated.resources.store_kind_sample
import dev.ide.ui.generated.resources.store_kind_template
import dev.ide.ui.generated.resources.store_use_template
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.TreeIcon
import dev.ide.ui.icons.TreeIcons
import dev.ide.ui.theme.resolveTint
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A full-screen detail page for a store item (a starter template or a sample project) — the richer successor
 * to the old detail bottom sheet. A colored hero header, an about section, "what you get" highlights, tags, a
 * specs table, and a pinned bottom CTA that creates the project (or installs a sample/community item).
 */
@Composable
fun StoreItemScreen(
    backend: IdeBackend,
    item: UiStoreItem?,
    onBack: () -> Unit,
    onCreateFromTemplate: (templateId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (item == null) { onBack(); return }
    val accent = item.accentColor?.let { Color(it) } ?: Ca.colors.accent
    val scope = rememberCoroutineScope()
    var busy by remember(item.id) { mutableStateOf(false) }
    var message by remember(item.id) { mutableStateOf<String?>(null) }

    Box(modifier.fillMaxSize().background(Ca.colors.bg), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Hero(item, accent, onBack)

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Screenshots: a scrollable gallery when the item ships several (community projects
                    // carry them in their .caproj), else the single built-in preview a sample game ships.
                    val gallery = item.screenshots.filter { hasSamplePreview(it) }
                    when {
                        gallery.isNotEmpty() -> Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            gallery.forEach { key ->
                                SamplePreview(key, Modifier.height(200.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(Ca.radius.lg)))
                            }
                        }
                        hasSamplePreview(item.previewKey) -> SamplePreview(
                            item.previewKey!!,
                            Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(Ca.radius.lg)),
                        )
                    }

                    // About
                    Section(stringResource(Res.string.store_item_about)) {
                        Text(item.description, color = Ca.colors.textSecondary, style = Ca.type.subhead)
                    }

                    // What you get
                    if (item.highlights.isNotEmpty()) {
                        Section(stringResource(Res.string.store_item_whats_included)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item.highlights.forEach { HighlightRow(it, accent) }
                            }
                        }
                    }

                    // Tags
                    if (item.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item.tags.forEach { Chip(it, fill = Ca.colors.surface2, textColor = Ca.colors.textSecondary) }
                        }
                    }

                    // Specs
                    Section(stringResource(Res.string.store_item_details)) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(Ca.radius.lg)).background(Ca.colors.surface)
                                .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.lg)),
                        ) {
                            SpecRow(stringResource(Res.string.store_item_type), kindLabel(item), first = true)
                            item.language?.let { SpecRow(stringResource(Res.string.store_item_language), it) }
                            SpecRow(stringResource(Res.string.store_item_publisher), item.author ?: "CodeAssist")
                            if (item.installs >= 0) SpecRow(stringResource(Res.string.store_item_downloads), compactCount(item.installs))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Pinned CTA bar.
            Column(
                Modifier.fillMaxWidth().background(Ca.colors.bg).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                message?.let { Text(it, color = Ca.colors.textTertiary, style = Ca.type.footnote) }
                when {
                    item.templateId != null -> PrimaryButton(
                        if (item.kind == UiStoreItemKind.Template) stringResource(Res.string.store_use_template) else stringResource(Res.string.store_create),
                        onClick = { onCreateFromTemplate(item.templateId!!) },
                        icon = CaIcons.plus,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    item.available -> PrimaryButton(
                        if (busy) stringResource(Res.string.store_installing) else stringResource(Res.string.store_get),
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
                    else -> DisabledBar(stringResource(Res.string.store_coming_soon))
                }
            }
        }
    }
}

@Composable
private fun Hero(item: UiStoreItem, accent: Color, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(230.dp).background(Brush.linearGradient(listOf(accent, accent.darken(0.6f))))) {
        // Decorative oversized glyph.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 8.dp)) {
            StoreGlyph(item.iconId, tile = 96.dp, glyph = 50.dp, fill = Color.White.copy(alpha = 0.14f), tint = Color.White)
        }
        IconButtonCa(
            CaIcons.chevronLeft, stringResource(Res.string.back), onBack,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp), tint = Color.White,
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(20.dp).fillMaxWidth(0.85f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(Ca.radius.pill)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(kindLabel(item).uppercase(), color = Color.White, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold)
            }
            Text(item.title, color = Color.White, style = Ca.type.title1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.category, color = Color.White.copy(alpha = 0.9f), style = Ca.type.footnote, fontWeight = FontWeight.Medium)
                item.author?.let {
                    Text("·", color = Color.White.copy(alpha = 0.7f), style = Ca.type.footnote)
                    Text(stringResource(Res.string.store_by_author, it), color = Color.White.copy(alpha = 0.9f), style = Ca.type.footnote)
                }
                if (item.installs >= 0) {
                    Text("·", color = Color.White.copy(alpha = 0.7f), style = Ca.type.footnote)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(CaIcons.download, null, Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.9f))
                        Text(compactCount(item.installs), color = Color.White.copy(alpha = 0.9f), style = Ca.type.caption2)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Ca.colors.textPrimary, style = Ca.type.title3)
        content()
    }
}

@Composable
private fun HighlightRow(text: String, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(Ca.radius.pill)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(CaIcons.check, null, Modifier.size(13.dp), tint = accent)
        }
        Text(text, color = Ca.colors.textSecondary, style = Ca.type.subhead)
    }
}

@Composable
private fun SpecRow(label: String, value: String, first: Boolean = false) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(Ca.colors.separator))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ca.colors.textTertiary, style = Ca.type.subhead, modifier = Modifier.weight(1f))
        Text(value, color = Ca.colors.textPrimary, style = Ca.type.subhead, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DisabledBar(text: String) {
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(Ca.radius.control)).background(Ca.colors.surface3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Ca.colors.textTertiary, style = Ca.type.subhead, fontWeight = FontWeight.SemiBold)
    }
}

// ---- shared with the store list ----

@Composable
private fun StoreGlyph(
    iconId: String,
    tile: androidx.compose.ui.unit.Dp,
    glyph: androidx.compose.ui.unit.Dp,
    fill: Color = Ca.colors.surface2,
    tint: Color? = null,
) {
    Box(Modifier.size(tile).clip(RoundedCornerShape(Ca.radius.lg)).background(fill), contentAlignment = Alignment.Center) {
        when (val ic = TreeIcons.resolve(iconId)) {
            is TreeIcon.Glyph -> Icon(ic.image, null, Modifier.size(glyph), tint = tint ?: resolveTint(ic.tint))
            is TreeIcon.Folder -> Icon(ic.closed, null, Modifier.size(glyph), tint = tint ?: resolveTint(ic.tint))
            is TreeIcon.Badge -> Text(ic.text, color = tint ?: ic.color, style = Ca.type.title2, fontWeight = FontWeight.Bold)
        }
    }
}

private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> "${n / 100_000 / 10.0}m"
    n >= 1_000 -> "${n / 100 / 10.0}k"
    else -> n.toString()
}

@Composable
private fun kindLabel(item: UiStoreItem): String = when (item.kind) {
    UiStoreItemKind.Template -> stringResource(Res.string.store_kind_template)
    UiStoreItemKind.Sample -> stringResource(Res.string.store_kind_sample)
    UiStoreItemKind.Community -> stringResource(Res.string.store_kind_community)
}
