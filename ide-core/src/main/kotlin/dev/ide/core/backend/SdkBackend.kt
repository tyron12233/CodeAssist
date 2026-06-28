package dev.ide.core.backend

import dev.ide.core.BackendContext
import dev.ide.ui.backend.SdkService
import dev.ide.ui.backend.UiAndroidSourcesInfo
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import kotlinx.coroutines.flow.StateFlow

/** [SdkService] over the engine's SDK manager: download Android SDK source packages + JDK sources for the
 *  editor (docs / go-to-source), plus the Android platform-sources status. */
internal class SdkBackend(private val ctx: BackendContext) : SdkService {
    override val sdkManagerState: StateFlow<UiSdkManagerState> = ctx.engineFlow(UiSdkManagerState()) { it.sdkManager.state }
    override suspend fun sdkPackages(): List<UiSdkPackage> = ctx.services.sdkManager.androidPackages()
    override suspend fun installSdkPackage(path: String): String = ctx.services.sdkManager.installAndroidPackage(path)
    override fun cancelSdkDownload(id: String) = ctx.services.sdkManager.cancelSdkDownload(id)
    override fun clearSdkDownloads() = ctx.services.sdkManager.clearSdkDownloads()
    override fun jdkInfo(): UiJdkInfo = ctx.services.sdkManager.jdkInfo()
    override suspend fun downloadJdkSources(feature: Int): String = ctx.services.sdkManager.downloadJdkSources(feature)

    override fun androidSourcesInfo(): UiAndroidSourcesInfo? =
        ctx.services.androidSourcesInfo()?.let { UiAndroidSourcesInfo(it.platform, it.installed, it.downloadable) }

    override suspend fun downloadAndroidSources(): String = ctx.services.downloadAndroidSources()
}
