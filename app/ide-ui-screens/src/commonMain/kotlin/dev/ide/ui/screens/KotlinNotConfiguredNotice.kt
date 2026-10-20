package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.IdeUiState
import dev.ide.ui.OpenFile
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.kotlin_configure
import dev.ide.ui.generated.resources.kotlin_configure_failed
import dev.ide.ui.generated.resources.kotlin_configuring
import dev.ide.ui.generated.resources.kotlin_not_configured
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * IntelliJ's "Kotlin not configured" prompt: a Kotlin source file whose module doesn't have the Kotlin standard
 * library yet (a Java module that just got its first `.kt` file), with an action that adds it. Until then the
 * Kotlin file still edits, but Java code in the module can't use the types Kotlin compiles to (`kotlin.Unit`,
 * the `Function` interfaces of its lambdas), and a build would add the library anyway.
 *
 * Null for any other file, for a module that is configured, and once dismissed for this tab. It checks again
 * whenever the project's files or model change, so configuring Kotlin some other way clears it too.
 */
@Composable
internal fun kotlinNotConfiguredNotice(state: IdeUiState, active: OpenFile): EditorNotice? {
    // Source files only: a `.kts` is a build script, and a library tab isn't part of any module.
    if (!active.name.endsWith(".kt") || active.libraryKind != null) return null
    val path = active.path
    val epoch by state.backend.files.fileSystemEpoch.collectAsState()
    var configured by remember(path) { mutableStateOf(true) }
    var busy by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var dismissed by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, epoch) {
        configured = runCatching { state.backend.modules.isKotlinConfigured(path) }.getOrDefault(true)
    }

    if (dismissed || (configured && failure == null)) return null
    val module = remember(path, epoch) { state.backend.files.moduleNameForFile(path) }.orEmpty()
    val failedTemplate = stringResource(Res.string.kotlin_configure_failed)
    val configuring = stringResource(Res.string.kotlin_configuring)
    val configure = stringResource(Res.string.kotlin_configure)
    return EditorNotice(
        id = "kotlin-not-configured",
        level = NoticeLevel.Warning,
        summary = failure ?: stringResource(Res.string.kotlin_not_configured, module),
        actionLabel = if (busy) configuring else configure,
        onAction = if (busy) null else ({
            busy = true
            failure = null
            scope.launch {
                val result = runCatching { state.backend.modules.configureKotlin(path) }
                    .getOrElse { UiConfigResult(false, it.message.toString()) }
                if (result.success) configured = true
                else failure = failedTemplate.replace("%1\$s", result.message)
                busy = false
            }
        }),
        onDismiss = { dismissed = true },
    )
}
