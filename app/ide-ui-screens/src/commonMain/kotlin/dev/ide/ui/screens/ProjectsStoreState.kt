package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStoreCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * State for the bundled catalogue the Projects Store browses when no remote feed is reachable.
 *
 * Browse only. Search moved to [StoreSearchState], which pages the store's own catalogue rather than
 * filtering this list, and is shared with the Explore feed so there is one search in the app.
 */
@Stable
internal class ProjectsStoreState(
    /** Also what the rows fetch a listing's app icon through, so it is readable rather than private. */
    val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var catalog: UiStoreCatalog by mutableStateOf(UiStoreCatalog())
        private set

    init {
        scope.launch { catalog = runCatching { backend.store.catalog() }.getOrDefault(UiStoreCatalog()) }
    }
}

@Composable
internal fun rememberProjectsStoreState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): ProjectsStoreState = remember(backend, scope) { ProjectsStoreState(backend, scope) }
