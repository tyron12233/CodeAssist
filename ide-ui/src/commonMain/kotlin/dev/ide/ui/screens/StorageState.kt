package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiStorageCategory
import dev.ide.ui.backend.UiStorageProject
import dev.ide.ui.backend.UiStorageReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * State and intents for the Storage screen: the usage report, the reclaim operations, and the two
 * destructive confirmations (clearing the SDK, deleting a project). Every reclaim recomputes the report.
 */
@Stable
internal class StorageScreenState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var report: UiStorageReport? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(true)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set

    /** How many bytes the last reclaim freed, for the confirmation toast (null once it is dismissed). */
    var freedBytes: Long? by mutableStateOf(null)
        private set

    /** Destructive confirmations, held until the user commits. */
    var pendingSdkClear: UiStorageCategory? by mutableStateOf(null)
        private set
    var pendingProjectDelete: UiStorageProject? by mutableStateOf(null)
        private set

    init {
        reload()
    }

    fun askClearSdk(category: UiStorageCategory) { pendingSdkClear = category }

    fun cancelClearSdk() { pendingSdkClear = null }

    fun askDeleteProject(project: UiStorageProject) { pendingProjectDelete = project }

    fun cancelDeleteProject() { pendingProjectDelete = null }

    fun dismissFreedToast() { freedBytes = null }

    /** Clear one category, report how much it freed, then recompute. */
    fun clear(category: UiStorageCategory) {
        if (busy) return
        scope.launch {
            busy = true
            val before = category.bytes
            backend.projects.clearStorageCategory(category.id)
            freedBytes = before
            reloadNow()
            busy = false
        }
    }

    /** Clear every non-destructive cache (everything except the SDK) in one pass. */
    fun clearAllCaches() {
        val current = report ?: return
        if (busy) return
        scope.launch {
            busy = true
            var freed = 0L
            current.categories.filter { it.clearable && !it.destructive }.forEach { category ->
                freed += category.bytes
                backend.projects.clearStorageCategory(category.id)
            }
            freedBytes = freed
            reloadNow()
            busy = false
        }
    }

    fun confirmClearSdk() {
        val category = pendingSdkClear
        pendingSdkClear = null
        category?.let(::clear)
    }

    fun confirmDeleteProject() {
        val project = pendingProjectDelete
        pendingProjectDelete = null
        if (project == null || busy) return
        scope.launch {
            busy = true
            backend.projects.deleteProject(project.rootPath)
            reloadNow()
            busy = false
        }
    }

    private fun reload() {
        scope.launch { reloadNow() }
    }

    private suspend fun reloadNow() {
        loading = true
        report = backend.projects.storageReport()
        loading = false
    }
}

@Composable
internal fun rememberStorageScreenState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): StorageScreenState = remember(backend, scope) { StorageScreenState(backend, scope) }
