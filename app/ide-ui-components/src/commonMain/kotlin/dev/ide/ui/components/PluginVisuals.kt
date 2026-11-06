package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.plugin_cap_group_access
import dev.ide.ui.generated.resources.plugin_cap_group_editor
import dev.ide.ui.generated.resources.plugin_cap_group_languages
import dev.ide.ui.generated.resources.plugin_cap_group_other
import dev.ide.ui.generated.resources.plugin_cap_group_projects
import dev.ide.ui.generated.resources.plugin_cap_group_ui
import dev.ide.ui.generated.resources.plugin_cap_sensitive
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A plugin's tile: its initials on a tonal square. A plugin has no icon the IDE can read before it runs (its
 * resources are another app's), so this gives every plugin a stable face of its own: the same id is always
 * the same colour, and two plugins side by side rarely share one.
 */
@Composable
fun PluginGlyph(name: String, id: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val tones = listOf(
        c.primaryContainer to c.onPrimaryContainer,
        c.secondaryContainer to c.onSecondaryContainer,
        c.tertiaryContainer to c.onTertiaryContainer,
        c.surfaceContainerHighest to c.primary,
    )
    val (fill, ink) = tones[(id.hashCode() and Int.MAX_VALUE) % tones.size]
    Box(
        modifier.size(size).background(fill, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            color = ink,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp,
            maxLines = 1,
        )
    }
}

/** One or two letters: the first of each of the first two words, skipping punctuation ("NDK (C/C++)" -> "NC"). */
internal fun initials(name: String): String {
    val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercaseChar() }
        else -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
    }
}

/** How the declared capabilities are grouped on a plugin's page and in the consent dialog. */
enum class CapabilityGroup(val label: StringResource, val glyph: Char) {
    Editor(Res.string.plugin_cap_group_editor, CaSymbols.codeBlocks),
    Languages(Res.string.plugin_cap_group_languages, CaSymbols.functions),
    Projects(Res.string.plugin_cap_group_projects, CaSymbols.construction),
    Screens(Res.string.plugin_cap_group_ui, CaSymbols.layers),
    Access(Res.string.plugin_cap_group_access, CaSymbols.folderOpen),
    Other(Res.string.plugin_cap_group_other, CaSymbols.extension),
}

fun capabilityGroup(capability: String): CapabilityGroup = when {
    capability.startsWith("ui.editor") || capability == "ui.colorAttribute" || capability == "ui.colorScheme" || capability == "ui.appTheme" || capability == "ui.keyBinding" -> CapabilityGroup.Editor
    capability.startsWith("ui.") -> CapabilityGroup.Screens
    capability.startsWith("lang.") -> CapabilityGroup.Languages
    capability.startsWith("model.") || capability.startsWith("build.") || capability == "interp.run" -> CapabilityGroup.Projects
    capability.startsWith("fs.") || capability == "net" -> CapabilityGroup.Access
    else -> CapabilityGroup.Other
}

/**
 * The capabilities worth a second look: changing the user's files, reaching the network, and running code or
 * programs (a language server, a build step, the user's own code). Marked, not hidden: every plugin runs with
 * the IDE's full access whatever it declares, and these are where that matters most.
 */
fun isSensitiveCapability(capability: String): Boolean = capability in SENSITIVE

private val SENSITIVE = setOf("fs.write", "model.dependencies", "net", "interp.run", "build.task", "build.runTask", "build.sourceGenerator", "lang.server")

/** [capabilities] grouped in [CapabilityGroup] order; an unknown one lands in Other, verbatim. */
fun groupCapabilities(capabilities: List<String>): List<Pair<CapabilityGroup, List<String>>> =
    capabilities.distinct().groupBy(::capabilityGroup).toList().sortedBy { it.first.ordinal }

/**
 * The declared capabilities as a grouped list: a header per group, a sentence per capability, sensitive ones
 * marked. Used by the plugin's page and by the consent dialog, so the two always say the same thing.
 */
@Composable
fun CapabilityList(capabilities: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((group, caps) in groupCapabilities(capabilities)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(group.glyph, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(group.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                for (capability in caps) CapabilityLine(capability)
            }
        }
    }
}

@Composable
private fun CapabilityLine(capability: String) {
    val sensitive = isSensitiveCapability(capability)
    Row(
        Modifier.fillMaxWidth().padding(start = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            describeCapability(capability),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (sensitive) {
            Chip(
                stringResource(Res.string.plugin_cap_sensitive),
                fill = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                textColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** A tonal status pill with a leading dot: what state a plugin is in, readable at a glance in a list. */
@Composable
fun PluginStatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .defaultMinSize(minHeight = 22.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(999.dp)))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}
