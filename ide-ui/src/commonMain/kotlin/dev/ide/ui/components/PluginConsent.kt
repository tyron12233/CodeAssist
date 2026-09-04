package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

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
            "Allow ${plugin.name} to run?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (plugin.origin.isNotBlank()) {
            Text(
                plugin.origin,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (plugin.capabilities.isNotEmpty()) {
            Text(
                "It says it will:",
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
                "A plugin runs inside CodeAssist, with the same access to your projects and account as the " +
                    "IDE itself. It is not sandboxed, and the list above is not enforced. Allow it only if " +
                    "you trust whoever published it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            publisherLine(plugin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onRefuse) { Text("Don't allow") }
            TextButton(onAccept) { Text("Allow") }
        }
    }
}

/**
 * Who signed the installed package. Read from the package manager, so it is a fact rather than a claim; an
 * unreadable certificate is stated plainly, because "we cannot tell who published this" is the most
 * important thing on the screen when it is true.
 */
internal fun publisherLine(plugin: UiPluginInfo): String {
    val signature = plugin.signature
    return if (signature == null) {
        "The publisher could not be identified: this package has no readable signing certificate."
    } else {
        "Signed by ${shortSignature(signature)}. An update from the same publisher reuses this certificate."
    }
}

/** A digest a person can compare at a glance, rather than 64 characters nobody reads. */
internal fun shortSignature(hex: String): String =
    if (hex.length <= 16) hex else "${hex.take(8)}…${hex.takeLast(8)}"

/**
 * A declared capability in words. An unrecognised value is shown verbatim rather than dropped: a capability
 * this build has never heard of is exactly the one the user should see.
 */
internal fun describeCapability(capability: String): String = when (capability) {
    "ui.action" -> "Add commands to the palette and menus"
    "ui.settingsPage" -> "Add a page to Settings"
    "ui.toolWindow" -> "Add a tool window to the IDE"
    "ui.screen" -> "Add a screen to the IDE"
    "ui.overlay" -> "Show a prompt over any screen"
    "ui.editorAction" -> "Add actions at the cursor in the editor"
    "ui.editorPreview" -> "Add a preview pane for some of your files"
    // Phrased as what it does to the user's own code, since that is the part worth deciding about.
    "interp.run" -> "Run your project's code inside the IDE, to preview or run it"
    "build.task" -> "Add steps to your builds"
    "build.sourceGenerator" -> "Generate source code into your modules"
    "build.runTask" -> "Add an entry to the Run picker"
    "fs.read" -> "Read the files in your projects"
    "fs.write" -> "Change the files in your projects"
    "net" -> "Make network requests"
    else -> capability
}
