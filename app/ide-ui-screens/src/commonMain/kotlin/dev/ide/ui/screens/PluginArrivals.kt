package dev.ide.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.ext.UiPluginHost
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.gradle_convert_done
import dev.ide.ui.generated.resources.plugins_arrival_on_body
import dev.ide.ui.generated.resources.plugins_arrival_on_title
import dev.ide.ui.generated.resources.plugins_arrival_restart_body
import dev.ide.ui.generated.resources.plugins_arrival_restart_title
import dev.ide.ui.generated.resources.plugins_restart_now
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** What became of a plugin the user allowed from [PluginArrivalReview]. */
private sealed interface Arrival {
    val name: String

    data class On(override val name: String) : Arrival

    data class NeedsRestart(override val name: String, val reason: String) : Arrival
}

/**
 * Asks about an installed plugin the user has not answered for, as soon as they come back to the app: the
 * moment after installing one from Get plugins. Before, the plugin showed nowhere until a restart, and once it
 * did nothing said it needed reviewing, so the user had to find it on the Plugins screen, allow it, and restart
 * again.
 *
 * Allow loads it at once (the backend takes an answer up live) and says so; Don't allow turns it off; a
 * dismissal is "not now" for this session, so the next launch asks again. Placed once in the shell, over every
 * screen.
 *
 * [onRestart] saves open work and restarts the app, for a plugin that could not be started live; null where the
 * host cannot restart itself.
 */
@Composable
fun PluginArrivalReview(backend: IdeBackend, onRestart: (() -> Unit)?) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<UiPluginInfo?>(null) }
    var result by remember { mutableStateOf<Arrival?>(null) }
    val notNow = remember { HashSet<String>() }

    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Coming back from the installer: the app takes the new package up off the main thread, which can finish
        // just after the window has its focus back, so look again shortly after.
        for (wait in longArrayOf(0L, 800L, 2500L)) {
            delay(wait)
            if (asking != null || result != null) return@LaunchedEffect
            val catalog = withContext(Dispatchers.Default) { runCatching { backend.settings.pluginCatalog() }.getOrDefault(emptyList()) }
            // A plugin updated or uninstalled meanwhile was already taken up by the backend; this brings its UI along.
            UiPluginHost.syncPlugins(backend.uiPlugins())
            asking = catalog.firstOrNull { !it.builtIn && it.togglable && it.needsConsent && it.id !in notNow }
        }
    }

    asking?.let { plugin ->
        PluginConsentDialog(
            plugin = plugin,
            onAnswer = { granted ->
                asking = null
                scope.launch {
                    val after = withContext(Dispatchers.Default) {
                        backend.settings.setPluginConsent(plugin.id, granted)
                        val row = backend.settings.pluginCatalog().firstOrNull { it.id == plugin.id }
                        val waiting = backend.settings.pendingPluginChanges().any { it.name == plugin.name }
                        row to waiting
                    }
                    UiPluginHost.syncPlugins(backend.uiPlugins())
                    if (!granted) return@launch
                    val (row, waiting) = after
                    result = if (row?.error == null && !waiting) Arrival.On(plugin.name)
                    else Arrival.NeedsRestart(plugin.name, row?.error.orEmpty())
                }
            },
            onDismiss = {
                notNow += plugin.id
                asking = null
            },
        )
    }

    result?.let { r ->
        val restart = onRestart?.takeIf { r is Arrival.NeedsRestart }
        AlertDialog(
            onDismissRequest = { result = null },
            title = {
                Text(
                    when (r) {
                        is Arrival.On -> stringResource(Res.string.plugins_arrival_on_title, r.name)
                        is Arrival.NeedsRestart -> stringResource(Res.string.plugins_arrival_restart_title, r.name)
                    },
                )
            },
            text = {
                Text(
                    when (r) {
                        is Arrival.On -> stringResource(Res.string.plugins_arrival_on_body)
                        is Arrival.NeedsRestart -> stringResource(Res.string.plugins_arrival_restart_body, r.reason)
                    },
                )
            },
            confirmButton = {
                if (restart != null) {
                    TextButton({ result = null; restart() }) { Text(stringResource(Res.string.plugins_restart_now)) }
                } else {
                    TextButton({ result = null }) { Text(stringResource(Res.string.gradle_convert_done)) }
                }
            },
            dismissButton = if (restart != null) ({
                TextButton({ result = null }) { Text(stringResource(Res.string.gradle_convert_done)) }
            }) else null,
        )
    }
}
