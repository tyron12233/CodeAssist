package dev.ide.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the SDK Manager last reported: a load failure, or a message from a start/cancel action. */
internal sealed interface SdkStatus {
    /** The package list could not be read; [message] is the failure's own text when it had one. */
    data class LoadFailed(val message: String?) : SdkStatus

    /** A backend-provided line (a download that started, or why it could not). */
    data class Message(val text: String) : SdkStatus
}

/**
 * State and intents for the SDK Manager: the installable packages, the JDK summary, the live download
 * queue, and the install/cancel actions. The list re-reads itself whenever a download finishes, since
 * downloads run in the background and change the installed flags.
 */
@Stable
internal class SdkManagerState(
    private val backend: IdeBackend,
    private val scope: CoroutineScope,
) {
    var packages: List<UiSdkPackage> by mutableStateOf(emptyList())
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var status: SdkStatus? by mutableStateOf(null)
        private set

    /** The live download queue and its progress. */
    var progress: UiSdkManagerState by mutableStateOf(backend.sdk.sdkManagerState.value)
        private set

    val jdk: UiJdkInfo? = runCatching { backend.sdk.jdkInfo() }.getOrNull()

    /** The packages with a download in flight, so their rows show progress instead of an Install action. */
    val activeIds: Set<String>
        get() = progress.downloads.filter { it.status != "DONE" && it.status != "FAILED" }.map { it.id }.toSet()

    val hasFinishedDownloads: Boolean
        get() = progress.downloads.any { it.status == "DONE" || it.status == "FAILED" }

    init {
        reload()
        scope.launch { backend.sdk.sdkManagerState.collect { progress = it } }
        // Refresh the installed/incomplete flags whenever a download finishes (it ran in the background).
        scope.launch {
            backend.sdk.sdkManagerState
                .map { state -> state.downloads.count { it.status == "DONE" } }
                .distinctUntilChanged()
                .collect { finished -> if (finished > 0) reloadNow() }
        }
    }

    fun reload() {
        scope.launch { reloadNow() }
    }

    fun install(pkg: UiSdkPackage) {
        scope.launch { status = SdkStatus.Message(backend.sdk.installSdkPackage(pkg.path)) }
    }

    fun downloadJdkSources(feature: Int) {
        scope.launch { status = SdkStatus.Message(backend.sdk.downloadJdkSources(feature)) }
    }

    fun cancel(id: String) = backend.sdk.cancelSdkDownload(id)

    fun clearFinished() = backend.sdk.clearSdkDownloads()

    private suspend fun reloadNow() {
        loading = true
        status = null
        val result = runCatching { backend.sdk.sdkPackages() }
        packages = result.getOrDefault(emptyList())
        if (result.isFailure) status = SdkStatus.LoadFailed(result.exceptionOrNull()?.message)
        loading = false
    }
}

@Composable
internal fun rememberSdkManagerState(
    backend: IdeBackend,
    scope: CoroutineScope = rememberCoroutineScope(),
): SdkManagerState = remember(backend, scope) { SdkManagerState(backend, scope) }
