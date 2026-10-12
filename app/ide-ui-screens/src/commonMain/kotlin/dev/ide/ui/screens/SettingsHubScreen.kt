package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.AdPlacement
import dev.ide.ui.components.AdSlot
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.LocalEmbeddedPane
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.back
import dev.ide.ui.generated.resources.home_clear_search
import dev.ide.ui.generated.resources.settings_search_empty
import dev.ide.ui.generated.resources.settings_search_placeholder
import dev.ide.ui.generated.resources.settings_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.joinMeta
import org.jetbrains.compose.resources.stringResource

/** Width from which the hub becomes a sidebar beside the open destination instead of a page of links. */
val HubTwoPaneBreakpoint = 840.dp

private val HubSidebarWidth = 280.dp

/**
 * Settings on a phone-width window: every destination in one grouped list, reachable from the project picker
 * (no project open) and from inside the editor. A settings page and a tool screen are both one tap away, as
 * siblings, so there is no inner "Settings" level to look through. Pure navigation: [onOpen] hands control back
 * to the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    sections: List<HubSection>,
    onBack: () -> Unit,
    onOpen: (HubTarget) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(CaIcons.chevronLeft, stringResource(Res.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            sections.forEachIndexed { index, section ->
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp)
                        .padding(top = if (index == 0) 4.dp else 20.dp),
                ) {
                    SectionHeader(section.title, Modifier.padding(start = 16.dp, bottom = 8.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        // Inner vertical padding so the first/last rows don't hug the card's rounded corners.
                        Column(Modifier.padding(vertical = 6.dp)) {
                            section.entries.forEachIndexed { i, entry ->
                                if (i > 0) HorizontalDivider(
                                    Modifier.padding(start = 72.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                                HubRow(entry) { onOpen(entry.target) }
                            }
                        }
                    }
                }
            }
            // A native ad under the destinations: a pure navigation/browse surface, no workflow to interrupt.
            AdSlot(
                AdPlacement.SETTINGS,
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun HubRow(entry: HubEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            // One consistent accent tone for every destination (no per-row color).
            Surface(shape = CircleShape, color = scheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(entry.icon, null, Modifier.size(20.dp), tint = scheme.onPrimaryContainer)
                }
            }
        },
        headlineContent = { Text(entry.title, style = MaterialTheme.typography.titleMedium) },
        // A project page leads with its scope, so a change is never mistaken for one to every project.
        supportingContent = listOfNotNull(entry.scopeLabel, entry.subtitle).takeIf { it.isNotEmpty() }?.let { parts ->
            { Text(parts.joinMeta(), style = MaterialTheme.typography.bodyMedium) }
        },
        trailingContent = {
            Icon(CaIcons.chevronRight, null, Modifier.size(18.dp), tint = scheme.outline)
        },
    )
}

/**
 * Settings on a wide window (from [HubTwoPaneBreakpoint]): the same grouped destinations as a persistent,
 * searchable sidebar, and the [selected] one open beside it, the way desktop settings windows work.
 *
 * [content] renders the selected destination's own screen under [LocalEmbeddedPane], so its frame drops the
 * back arrow and the collapsing title. Back from here leaves Settings entirely ([onBack]).
 */
@Composable
fun SettingsHubPane(
    sections: List<HubSection>,
    selected: HubTarget,
    onSelect: (HubTarget) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val shown = filterHub(sections, query)
    Row(modifier.fillMaxSize().background(scheme.background)) {
        Column(Modifier.width(HubSidebarWidth).fillMaxHeight().background(scheme.surfaceContainerLow)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(CaIcons.chevronLeft, stringResource(Res.string.back))
                }
                Text(
                    stringResource(Res.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            HubSearchField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        stringResource(Res.string.settings_search_empty, query.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.outline,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                shown.forEachIndexed { index, section ->
                    SectionHeader(
                        section.title,
                        Modifier.padding(start = 12.dp, top = if (index == 0) 4.dp else 16.dp, bottom = 4.dp),
                    )
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        section.entries.forEach { e ->
                            HubSidebarItem(e, e.target == selected) { onSelect(e.target) }
                        }
                    }
                }
                AdSlot(AdPlacement.SETTINGS, Modifier.fillMaxWidth().padding(top = 24.dp))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalEmbeddedPane provides true) { content() }
        }
    }
}

@Composable
private fun HubSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(50)).background(c.surfaceContainerHigh).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CaIcons.search, null, Modifier.size(18.dp), tint = c.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(Res.string.settings_search_placeholder), style = MaterialTheme.typography.bodyMedium, color = c.outline)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButtonCa(CaIcons.close, stringResource(Res.string.home_clear_search), { onQuery("") }, iconSize = 14, boxSize = 24)
        }
    }
}

@Composable
private fun HubSidebarItem(entry: HubEntry, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { role = Role.Tab },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(entry.icon, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Pages that change only the open project say so, so a change is never mistaken for a global one.
                if (entry.scopeLabel != null) {
                    Text(
                        entry.scopeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) scheme.onSecondaryContainer else scheme.outline,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
