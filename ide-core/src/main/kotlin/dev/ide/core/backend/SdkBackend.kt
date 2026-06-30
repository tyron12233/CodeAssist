package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.SdkService
import dev.ide.ui.backend.UiAndroidSourcesInfo
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** [SdkService] over the APPLICATION-scoped SDK manager ([BackendContext.sdkManager]): download Android SDK
 *  source packages + JDK sources for the editor (docs / go-to-source), plus the Android platform-sources
 *  status. Because the manager is app-scoped (one shared download queue), this works from the project
 *  picker's Settings & Tools hub with no project open. JDK-source/Android-source installs run on a
 *  background scope inside the manager; the screen observes [sdkManagerState]. */
internal class SdkBackend(private val ctx: BackendContext) : SdkService {
    private val empty: StateFlow<UiSdkManagerState> = MutableStateFlow(UiSdkManagerState()).asStateFlow()

    override val sdkManagerState: StateFlow<UiSdkManagerState> get() = ctx.sdkManager?.state ?: empty
    override suspend fun sdkPackages(): List<UiSdkPackage> = ctx.sdkManager?.androidPackages() ?: emptyList()
    override suspend fun installSdkPackage(path: String): String =
        ctx.sdkManager?.installAndroidPackage(path) ?: "No SDK manager available."
    override fun cancelSdkDownload(id: String) { ctx.sdkManager?.cancelSdkDownload(id) }
    override fun clearSdkDownloads() { ctx.sdkManager?.clearSdkDownloads() }
    override fun jdkInfo(): UiJdkInfo? = ctx.sdkManager?.jdkInfo()
    override suspend fun downloadJdkSources(feature: Int): String =
        ctx.sdkManager?.downloadJdkSources(feature) ?: "No SDK manager available."

    override fun androidSourcesInfo(): UiAndroidSourcesInfo? =
        ctx.sdkManager?.androidSourcesInfo()?.let { UiAndroidSourcesInfo(it.platform, it.installed, it.downloadable) }

    override suspend fun downloadAndroidSources(): String =
        withContext(Dispatchers.IO) { ctx.sdkManager?.downloadAndroidSources() ?: "No SDK manager available." }
}
