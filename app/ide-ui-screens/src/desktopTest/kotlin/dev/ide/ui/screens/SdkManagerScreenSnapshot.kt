package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.SdkFailureKind
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkDownload
import dev.ide.ui.backend.UiSdkFailure
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the SDK Manager off-screen: the folded package list (installed, the project's compileSdk and the
 * newest up front, the rest behind "Show all versions"), and the failure state (a plain-language reason, the
 * matching fix, Details). The render throwing is the failure this guards; the PNGs are for eyeballing.
 */
class SdkManagerScreenSnapshot {

    private fun platform(level: String, installed: Boolean = false, size: Long = 60) = UiSdkPackage(
        path = "platforms;android-$level", displayName = "Android SDK Platform $level", category = "PLATFORMS",
        sizeBytes = size shl 20, installed = installed, installable = true,
    )

    private fun sources(level: Int, installed: Boolean = false) = UiSdkPackage(
        path = "sources;android-$level", displayName = "Sources for Android $level", category = "SOURCES",
        sizeBytes = 45L shl 20, installed = installed, installable = true,
    )

    private val packages =
        listOf("37.2", "36.1", "36", "35", "34", "33", "32", "31", "30", "29", "28", "27", "26", "25", "24", "23", "22", "21")
            .map { platform(it, installed = it == "26" || it == "22") } +
            (26..37).map { sources(it, installed = it == 34) }

    private inner class FakeBackend(downloads: List<UiSdkDownload>) : StubBackend() {
        private val flow = MutableStateFlow(UiSdkManagerState(downloads = downloads))
        override val sdkManagerState: StateFlow<UiSdkManagerState> get() = flow
        override suspend fun sdkPackages(): List<UiSdkPackage> = packages
        override suspend fun sdkProjectPlatformLevels(): Set<Int> = setOf(34)
        override fun jdkInfo(): UiJdkInfo = UiJdkInfo("/apex/com.android.art", "0", null, androidRuntime = true)
    }

    @Test
    fun renderFoldedList() {
        snapshot("sdk-manager-list.png", 822, 1780, FakeBackend(emptyList()))
        snapshot("sdk-manager-list-full.png", 822, 3000, FakeBackend(emptyList()))
    }

    @Test
    fun renderFailures() {
        val downloads = listOf(
            UiSdkDownload(
                "platforms;android-25", "Android SDK Platform 25", "FAILED",
                detail = "Install failed: UnknownHostException: Unable to resolve host \"dl.google.com\": No address associated with hostname",
                failure = UiSdkFailure(SdkFailureKind.OFFLINE),
            ),
            UiSdkDownload(
                "platforms;android-33", "Android SDK Platform 33", "FAILED",
                detail = "Install failed: SdkHttpException: HTTP 503 from https://dl.google.com/android/repository/platform-33-ext3_r03.zip",
                failure = UiSdkFailure(SdkFailureKind.SERVER, 503),
            ),
            UiSdkDownload("sources;android-35", "Sources for Android 35", "DOWNLOADING", fraction = 0.42, detail = "19.0 MB / 45.0 MB"),
        )
        snapshot("sdk-manager-failures.png", 822, 1780, FakeBackend(downloads))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, w: Int, h: Int, backend: FakeBackend) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    SdkManagerScreen(backend, onBack = {}, onOpenStorage = {})
                }
            }
        }
        try {
            // Pump frames so the package-list and project-level loads resolve before capture.
            scene.render()
            scene.render(50_000_000L)
            scene.render(150_000_000L)
            val img = scene.render(400_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: ${File("$OUT_DIR/$name").absolutePath} (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/snapshots"
    }
}
