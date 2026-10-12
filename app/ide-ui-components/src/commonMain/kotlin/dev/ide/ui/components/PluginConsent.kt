package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.allow
import dev.ide.ui.generated.resources.plugin_consent_cap_build_run_task
import dev.ide.ui.generated.resources.plugin_consent_cap_build_source_generator
import dev.ide.ui.generated.resources.plugin_consent_cap_build_task
import dev.ide.ui.generated.resources.plugin_consent_cap_fs_read
import dev.ide.ui.generated.resources.plugin_consent_cap_fs_write
import dev.ide.ui.generated.resources.plugin_consent_cap_interp_run
import dev.ide.ui.generated.resources.plugin_consent_cap_lang_backend
import dev.ide.ui.generated.resources.plugin_consent_cap_model_facet
import dev.ide.ui.generated.resources.plugin_consent_cap_model_module_type
import dev.ide.ui.generated.resources.plugin_consent_cap_net
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_action
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_color_attribute
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_editor_action
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_editor_decoration
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_editor_language
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_editor_layer
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_editor_painter
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_editor_preview
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_key_binding
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_new_file_template
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_overlay
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_screen
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_settings_page
import dev.ide.ui.generated.resources.plugin_consent_cap_ui_tool_window
import dev.ide.ui.generated.resources.plugin_consent_declares
import dev.ide.ui.generated.resources.plugin_consent_deny
import dev.ide.ui.generated.resources.plugin_consent_publisher_unknown
import dev.ide.ui.generated.resources.plugin_consent_signed_by
import dev.ide.ui.generated.resources.plugin_consent_title
import dev.ide.ui.generated.resources.plugin_consent_warning
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The question asked before an installed plugin is allowed to run.
 *
 * Finding a plugin app on the device is not the user agreeing to execute it. Its classes are loaded into the
 * IDE's own process, so it runs with the IDE's permissions and its access to the user's projects and
 * account; class loading separates versions, not privileges. The OS install prompt the user already saw was
 * about installing an app, not about letting that app inside this one, so this is a separate decision.
 *
 * The wording keeps the two halves apart deliberately. What the plugin *declares* is a claim, because
 * nothing enforces the capability list yet, and presenting it as "permissions" would imply a sandbox that
 * does not exist. What is stated as fact is the part that is true of every plugin regardless of what it
 * declared.
 */
@Composable
fun PluginConsent(
    plugin: UiPluginInfo,
    onRefuse: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            stringResource(Res.string.plugin_consent_title, plugin.name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        // Only the middle scrolls: a long capability list must not push the answer buttons out of a dialog
        // that is already as tall as the screen allows. fill = false keeps a short body from stretching.
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (plugin.origin.isNotBlank()) {
                Text(
                    plugin.origin,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (plugin.capabilities.isNotEmpty()) {
                Text(
                    stringResource(Res.string.plugin_consent_declares),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (capability in plugin.capabilities) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(
                                CaIcons.check,
                                null,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                describeCapability(capability),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.md))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(CaIcons.info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(Res.string.plugin_consent_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                publisherLine(plugin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onRefuse) { Text(stringResource(Res.string.plugin_consent_deny)) }
            TextButton(onAccept) { Text(stringResource(Res.string.allow)) }
        }
    }
}

/**
 * Who signed the installed package. Read from the package manager, so it is a fact rather than a claim; an
 * unreadable certificate is stated plainly, because "we cannot tell who published this" is the most
 * important thing on the screen when it is true.
 */
@Composable
internal fun publisherLine(plugin: UiPluginInfo): String {
    val signature = plugin.signature
    return if (signature == null) {
        stringResource(Res.string.plugin_consent_publisher_unknown)
    } else {
        stringResource(Res.string.plugin_consent_signed_by, shortSignature(signature))
    }
}

/** A digest a person can compare at a glance, rather than 64 characters nobody reads. */
internal fun shortSignature(hex: String): String =
    if (hex.length <= 16) hex else "${hex.take(8)}…${hex.takeLast(8)}"

/**
 * A declared capability in words. An unrecognised value is shown verbatim rather than dropped: a capability
 * this build has never heard of is exactly the one the user should see.
 */
@Composable
internal fun describeCapability(capability: String): String =
    capabilityDescription(capability)?.let { stringResource(it) } ?: capability

/** The sentence for a declared capability, or null for one this build does not know. */
internal fun capabilityDescription(capability: String): StringResource? = when (capability) {
    "ui.action" -> Res.string.plugin_consent_cap_ui_action
    "ui.settingsPage" -> Res.string.plugin_consent_cap_ui_settings_page
    "ui.toolWindow" -> Res.string.plugin_consent_cap_ui_tool_window
    "ui.screen" -> Res.string.plugin_consent_cap_ui_screen
    "ui.overlay" -> Res.string.plugin_consent_cap_ui_overlay
    "ui.editorAction" -> Res.string.plugin_consent_cap_ui_editor_action
    "ui.editorPreview" -> Res.string.plugin_consent_cap_ui_editor_preview
    "ui.keyBinding" -> Res.string.plugin_consent_cap_ui_key_binding
    // The three that change how the user's own code looks, phrased so the difference between them is the
    // part a reader can act on: what is marked, what is added beside it, and what is painted over it.
    "ui.editorDecoration" -> Res.string.plugin_consent_cap_ui_editor_decoration
    "ui.editorLayer" -> Res.string.plugin_consent_cap_ui_editor_layer
    "ui.editorPainter" -> Res.string.plugin_consent_cap_ui_editor_painter
    "ui.editorLanguage" -> Res.string.plugin_consent_cap_ui_editor_language
    "ui.colorAttribute" -> Res.string.plugin_consent_cap_ui_color_attribute
    "ui.newFileTemplate" -> Res.string.plugin_consent_cap_ui_new_file_template
    "lang.backend" -> Res.string.plugin_consent_cap_lang_backend
    "model.moduleType" -> Res.string.plugin_consent_cap_model_module_type
    "model.facet" -> Res.string.plugin_consent_cap_model_facet
    // Phrased as what it does to the user's own code, since that is the part worth deciding about.
    "interp.run" -> Res.string.plugin_consent_cap_interp_run
    "build.task" -> Res.string.plugin_consent_cap_build_task
    "build.sourceGenerator" -> Res.string.plugin_consent_cap_build_source_generator
    "build.runTask" -> Res.string.plugin_consent_cap_build_run_task
    "fs.read" -> Res.string.plugin_consent_cap_fs_read
    "fs.write" -> Res.string.plugin_consent_cap_fs_write
    "net" -> Res.string.plugin_consent_cap_net
    else -> null
}
