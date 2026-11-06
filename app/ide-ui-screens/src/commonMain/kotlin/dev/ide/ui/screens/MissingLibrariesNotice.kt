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
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiMissingLibrary
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.missing_libraries_add_all
import dev.ide.ui.generated.resources.missing_libraries_adding
import dev.ide.ui.generated.resources.missing_libraries_notice
import dev.ide.ui.generated.resources.missing_library_failed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "This file uses libraries that aren't added yet: Gson, Retrofit · Add all", for a file missing two or more
 * libraries (pasted code, typically). One missing library is the lightbulb's job ("Add library …" on the
 * import); the notice is for when fixing them one at a time would be a chore.
 *
 * It also reports an add started from that lightbulb fix that failed, since the fix runs in the background
 * and would otherwise fail silently.
 *
 * Asks the backend again whenever the file's errors change, a library finishes resolving, or an online
 * lookup learns which library an import comes from.
 */
@Composable
internal fun missingLibrariesNotice(state: IdeUiState, active: OpenFile): EditorNotice? {
    if (active.libraryKind != null || active.readOnly) return null
    val path = active.path
    val deps = state.backend.deps
    val errors = active.session.diagnostics.count { it.severity == UiSeverity.Error }
    val lookups by deps.missingLibraryLookups.collectAsState()
    val resolving = deps.depsState.collectAsState().value.resolving
    val outcome by deps.missingLibraryOutcome.collectAsState()
    var missing by remember(path) { mutableStateOf<List<UiMissingLibrary>>(emptyList()) }
    var busy by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var dismissed by remember(path) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path, errors, lookups, resolving) {
        if (errors == 0) { missing = emptyList(); return@LaunchedEffect }
        if (resolving) return@LaunchedEffect
        delay(400)   // let the analysis that changed the count publish its diagnostics
        missing = runCatching { deps.missingLibraries(path, active.text) }.getOrDefault(emptyList())
    }

    val failedTemplate = stringResource(Res.string.missing_library_failed)
    val failed = outcome?.takeIf { !it.success }
    if (failed != null) {
        return EditorNotice(
            id = "missing-library-failed",
            level = NoticeLevel.Warning,
            summary = failedTemplate.replace("%1\$s", failed.message),
            onDismiss = { deps.clearMissingLibraryOutcome() },
        )
    }
    if (dismissed || (missing.size < 2 && failure == null)) return null
    val adding = stringResource(Res.string.missing_libraries_adding)
    val addAll = stringResource(Res.string.missing_libraries_add_all)
    return EditorNotice(
        id = "missing-libraries",
        level = NoticeLevel.Info,
        summary = failure ?: stringResource(Res.string.missing_libraries_notice, missing.joinToString(", ") { it.label }),
        actionLabel = if (busy) adding else addAll,
        onAction = if (busy) null else ({
            busy = true
            failure = null
            scope.launch {
                val result = runCatching { deps.addMissingLibraries(path, active.text) }
                    .getOrElse { UiAddResult(false, it.message.toString()) }
                if (result.success) missing = emptyList()
                else failure = failedTemplate.replace("%1\$s", result.message)
                busy = false
            }
        }),
        onDismiss = { dismissed = true },
    )
}
