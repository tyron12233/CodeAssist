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
import dev.ide.ui.backend.UiSdkDownload
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

/** A package waiting on its license: [text] is the license wording, null when it could not be loaded. */
internal data class LicensePrompt(val pkg: UiSdkPackage, val license: String, val text: String?)

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

    /** The license the user is being asked to accept before an install starts, or null. */
    var licensePrompt: LicensePrompt? by mutableStateOf(null)
        private set

    /** The compileSdk levels of the open project's Android modules: their platforms and sources are listed
     *  up front. Empty with no project open. */
    var neededLevels: Set<Int> by mutableStateOf(emptySet())
        private set

    /** Package categories whose "Show all versions" expander is open. */
    var expanded: Set<String> by mutableStateOf(emptySet())
        private set

    /** The packages with a download in flight, so their rows show progress instead of an Install action. */
    val activeIds: Set<String>
        get() = progress.downloads.filter { it.status != "DONE" && it.status != "FAILED" }.map { it.id }.toSet()

    /** The packages whose last download failed, kept in view so their row is easy to find again. */
    val failedIds: Set<String>
        get() = progress.downloads.filter { it.status == "FAILED" }.map { it.id }.toSet()

    val hasFinishedDownloads: Boolean
        get() = progress.downloads.any { it.status == "DONE" || it.status == "FAILED" }

    init {
        reload()
        scope.launch { neededLevels = runCatching { backend.sdk.sdkProjectPlatformLevels() }.getOrDefault(emptySet()) }
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

    /** Install [pkg], first asking for its license when the user has not accepted it yet. */
    fun install(pkg: UiSdkPackage) {
        val license = pkg.license
        if (license != null && !pkg.licenseAccepted) {
            scope.launch { licensePrompt = LicensePrompt(pkg, license, runCatching { backend.sdk.sdkLicenseText(license) }.getOrNull()) }
            return
        }
        scope.launch { start(pkg.path) { backend.sdk.installSdkPackage(pkg.path) } }
    }

    /** Start [failed] again: a package goes back through [install] (which asks for its license if needed), JDK
     *  sources re-download. The partial file a failed or cancelled download left is resumed. */
    fun retry(failed: UiSdkDownload) {
        val feature = failed.id.removePrefix("jdk-").takeIf { failed.id.startsWith("jdk-") }?.toIntOrNull()
        if (feature != null) return downloadJdkSources(feature)
        val pkg = packages.firstOrNull { it.path == failed.id }
        if (pkg != null) install(pkg) else scope.launch { start(failed.id) { backend.sdk.installSdkPackage(failed.id) } }
    }

    fun toggleExpanded(category: String) {
        expanded = if (category in expanded) expanded - category else expanded + category
    }

    /**
     * Run a start action whose reply is a status line. A download that started shows up in the queue, which
     * tracks it from there, so its "Downloading…" reply is not kept: a line that outlived the download is how
     * the screen once read "Downloading" above the very row that said it failed. Only a refusal is shown.
     */
    private suspend fun start(id: String, action: suspend () -> String) {
        val reply = action()
        val queued = backend.sdk.sdkManagerState.value.downloads.any { it.id == id && it.status != "DONE" && it.status != "FAILED" }
        status = if (queued) null else SdkStatus.Message(reply)
    }

    /** Accept the prompted license and start the install that was waiting on it. */
    fun acceptLicense() {
        val prompt = licensePrompt ?: return
        licensePrompt = null
        scope.launch {
            backend.sdk.acceptSdkLicense(prompt.license)
            reloadNow() // the row's license state changed
            start(prompt.pkg.path) { backend.sdk.installSdkPackage(prompt.pkg.path) }
        }
    }

    fun declineLicense() {
        licensePrompt = null
    }

    fun downloadJdkSources(feature: Int) {
        scope.launch { start("jdk-$feature") { backend.sdk.downloadJdkSources(feature) } }
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
