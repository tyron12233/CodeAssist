package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ide.ui.backend.UiSettingsPage
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.settings_code_style
import dev.ide.ui.generated.resources.settings_code_style_subtitle
import dev.ide.ui.generated.resources.settings_colors
import dev.ide.ui.generated.resources.settings_colors_subtitle
import dev.ide.ui.generated.resources.settings_group_build
import dev.ide.ui.generated.resources.settings_group_code
import dev.ide.ui.generated.resources.settings_group_editor
import dev.ide.ui.generated.resources.settings_group_extensions
import dev.ide.ui.generated.resources.settings_group_system
import dev.ide.ui.generated.resources.settings_keystore_manager
import dev.ide.ui.generated.resources.settings_keystore_manager_subtitle
import dev.ide.ui.generated.resources.settings_page_appearance_subtitle
import dev.ide.ui.generated.resources.settings_page_editor_subtitle
import dev.ide.ui.generated.resources.settings_page_keymap_subtitle
import dev.ide.ui.generated.resources.settings_page_completion_subtitle
import dev.ide.ui.generated.resources.settings_page_analysis_subtitle
import dev.ide.ui.generated.resources.settings_page_preview_subtitle
import dev.ide.ui.generated.resources.settings_page_build_subtitle
import dev.ide.ui.generated.resources.settings_page_buildRuntime_subtitle
import dev.ide.ui.generated.resources.settings_page_privacy_subtitle
import dev.ide.ui.generated.resources.settings_plugins
import dev.ide.ui.generated.resources.settings_plugins_subtitle
import dev.ide.ui.generated.resources.settings_scope_project
import dev.ide.ui.generated.resources.settings_sdk_manager
import dev.ide.ui.generated.resources.settings_sdk_manager_subtitle
import dev.ide.ui.generated.resources.settings_storage
import dev.ide.ui.generated.resources.settings_storage_subtitle
import dev.ide.ui.generated.resources.settings_symbols
import dev.ide.ui.generated.resources.settings_symbols_subtitle
import dev.ide.ui.icons.CaIcons
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A destination of the Settings hub that is its own screen rather than a page of generated controls. */
enum class HubTool { Colors, CodeStyle, Symbols, SdkManager, Keystore, Plugins, Storage }

/** What one hub entry opens: a settings page (built-in or plugin-contributed) by id, or a [HubTool]. */
sealed interface HubTarget {
    data class Page(val id: String) : HubTarget
    data class Tool(val tool: HubTool) : HubTarget
}

/** The hub's sections, named for what the user is trying to do rather than for how a screen is built. */
enum class HubGroup(internal val label: StringResource) {
    Editor(Res.string.settings_group_editor),
    Code(Res.string.settings_group_code),
    Build(Res.string.settings_group_build),
    Extensions(Res.string.settings_group_extensions),
    System(Res.string.settings_group_system),
}

/**
 * Where every known destination sits, in order. Generated pages and tool screens are siblings: Editor Colors
 * belongs beside Appearance and the Keystore Manager beside the build pages, because that is where someone
 * looks for them. A page id listed here only shows when the backend offers it (the project-scoped pages are
 * absent with no project open). Ids mirror `BuiltInSettingsPages` and the bundled plugins' pages.
 */
private val HubSlots: List<Pair<HubGroup, List<HubTarget>>> = listOf(
    HubGroup.Editor to listOf(
        HubTarget.Page("appearance"),
        HubTarget.Tool(HubTool.Colors),
        HubTarget.Page("editor"),
        HubTarget.Tool(HubTool.CodeStyle),
        HubTarget.Page("keymap"),
        HubTarget.Tool(HubTool.Symbols),
    ),
    HubGroup.Code to listOf(
        HubTarget.Page("completion"),
        HubTarget.Page("analysis"),
    ),
    HubGroup.Build to listOf(
        HubTarget.Page("build"),
        HubTarget.Page("preview"),
        HubTarget.Page("buildRuntime"),
        HubTarget.Tool(HubTool.SdkManager),
        HubTarget.Tool(HubTool.Keystore),
    ),
    HubGroup.Extensions to listOf(
        HubTarget.Tool(HubTool.Plugins),
    ),
    HubGroup.System to listOf(
        HubTarget.Tool(HubTool.Storage),
        HubTarget.Page("privacy"),
    ),
)

/**
 * Places [pageIds] (in the backend's order) into [HubSlots]. A page with no slot, which is any page a plugin
 * contributes, goes to [HubGroup.Extensions] after Plugins, so a newly installed plugin's settings land next
 * to the screen that manages the plugin. Slotted pages the backend did not offer are dropped.
 */
internal fun hubPlacement(pageIds: List<String>): List<Pair<HubGroup, List<HubTarget>>> {
    val offered = pageIds.toSet()
    val slotted = HubSlots.flatMap { it.second }.filterIsInstance<HubTarget.Page>().map { it.id }.toSet()
    val unslotted = pageIds.filter { it !in slotted }.map { HubTarget.Page(it) }
    return HubSlots.map { (group, targets) ->
        val present = targets.filter { it !is HubTarget.Page || it.id in offered }
        group to if (group == HubGroup.Extensions) present + unslotted else present
    }.filter { it.second.isNotEmpty() }
}

/** What a built-in page holds, shown under its title. Plugin pages have none. */
private val PageSubtitles: Map<String, StringResource> = mapOf(
    "appearance" to Res.string.settings_page_appearance_subtitle,
    "editor" to Res.string.settings_page_editor_subtitle,
    "keymap" to Res.string.settings_page_keymap_subtitle,
    "completion" to Res.string.settings_page_completion_subtitle,
    "analysis" to Res.string.settings_page_analysis_subtitle,
    "preview" to Res.string.settings_page_preview_subtitle,
    "build" to Res.string.settings_page_build_subtitle,
    "buildRuntime" to Res.string.settings_page_buildRuntime_subtitle,
    "privacy" to Res.string.settings_page_privacy_subtitle,
)

/**
 * One resolved hub row. [scopeLabel] is set on a page that changes only the open project. [keywords] widen the
 * wide hub's search to the labels of a page's own controls.
 */
class HubEntry(
    val target: HubTarget,
    val icon: ImageVector,
    val title: String,
    val subtitle: String?,
    val scopeLabel: String?,
    internal val keywords: List<String>,
)

class HubSection(val group: HubGroup, val title: String, val entries: List<HubEntry>)

/** The hub's sections for [pages], with titles, icons and subtitles resolved for display. */
@Composable
fun hubSections(pages: List<UiSettingsPage>): List<HubSection> {
    val byId = pages.associateBy { it.id }
    val projectLabel = stringResource(Res.string.settings_scope_project)
    return hubPlacement(pages.map { it.id }).map { (group, targets) ->
        HubSection(
            group = group,
            title = stringResource(group.label),
            entries = targets.map { target ->
                when (target) {
                    is HubTarget.Page -> {
                        val page = byId.getValue(target.id)
                        val project = page.scope == "project"
                        val about = PageSubtitles[page.id]?.let { stringResource(it) }
                        HubEntry(
                            target = target,
                            icon = settingsPageIcon(page.iconId),
                            title = localizedPageTitle(page),
                            subtitle = about,
                            scopeLabel = if (project) projectLabel else null,
                            keywords = page.controls.flatMap { c -> listOfNotNull(c.title, c.group) },
                        )
                    }
                    is HubTarget.Tool -> toolEntry(target)
                }
            },
        )
    }
}

@Composable
private fun toolEntry(target: HubTarget.Tool): HubEntry {
    val (icon, title, subtitle) = when (target.tool) {
        HubTool.Colors -> Triple(CaIcons.eye, Res.string.settings_colors, Res.string.settings_colors_subtitle)
        HubTool.CodeStyle -> Triple(CaIcons.braces, Res.string.settings_code_style, Res.string.settings_code_style_subtitle)
        HubTool.Symbols -> Triple(CaIcons.grid, Res.string.settings_symbols, Res.string.settings_symbols_subtitle)
        HubTool.SdkManager -> Triple(CaIcons.pkg, Res.string.settings_sdk_manager, Res.string.settings_sdk_manager_subtitle)
        HubTool.Keystore -> Triple(CaIcons.key, Res.string.settings_keystore_manager, Res.string.settings_keystore_manager_subtitle)
        HubTool.Plugins -> Triple(CaIcons.box, Res.string.settings_plugins, Res.string.settings_plugins_subtitle)
        HubTool.Storage -> Triple(CaIcons.layers, Res.string.settings_storage, Res.string.settings_storage_subtitle)
    }
    return HubEntry(target, icon, stringResource(title), stringResource(subtitle), scopeLabel = null, keywords = emptyList())
}

/** The entries of [sections] matching [query] by title, subtitle, section or a page's control labels. */
internal fun filterHub(sections: List<HubSection>, query: String): List<HubSection> {
    val q = query.trim()
    if (q.isEmpty()) return sections
    return sections.mapNotNull { section ->
        val sectionHit = section.title.contains(q, ignoreCase = true)
        val hits = section.entries.filter { e ->
            sectionHit || e.title.contains(q, ignoreCase = true) ||
                e.subtitle?.contains(q, ignoreCase = true) == true ||
                e.keywords.any { it.contains(q, ignoreCase = true) }
        }
        if (hits.isEmpty()) null else HubSection(section.group, section.title, hits)
    }
}
