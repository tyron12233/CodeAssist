package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import dev.ide.ui.StubBackend
import dev.ide.ui.ads.LocalAds
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.fakeAdController
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the redesigned [ProjectsHomeScreen] off-screen: the greeting eyebrow over the display title, the
 * account tile, the asymmetric New-project / Clone pair, the segment chips with their counts, the project
 * cards with their rotating tiles and watermarks, and the Resume card.
 *
 * The empty case is rendered too, because it is the state two of the three segments are permanently in
 * until the store backend lands and is therefore the one most likely to regress unnoticed.
 */
class ProjectsHomeScreenSnapshot {

    private val projects = listOf(
        project("aurora-app", "/storage/emulated/0/CodeAssist/dev/aurora-app", 4, android = true, hoursAgo = 2),
        project("ledger-service", "/storage/emulated/0/CodeAssist/dev/ledger-service", 2, hoursAgo = 26),
        project("algo-coursework", "/storage/emulated/0/CodeAssist/school/algo-coursework", 1, compat = true, hoursAgo = 72),
        project("fx-notes", "/storage/emulated/0/CodeAssist/dev/fx-notes", 1, hoursAgo = 240),
    )

    private fun project(
        name: String, path: String, modules: Int,
        android: Boolean = false, compat: Boolean = false, hoursAgo: Int,
    ) = ProjectInfo(
        name = name, rootPath = path, moduleCount = modules,
        compatibility = compat, isAndroid = android,
        // Fixed offsets from a rendered "now" would drift; the screen reads nowMillis() itself, so these
        // are computed against the same clock.
        lastOpened = System.currentTimeMillis() - hoursAgo * 3_600_000L,
    )

    @Test
    fun renderDark() = snapshot("home-dark.png", projects, dark = true)

    @Test
    fun renderLight() = snapshot("home-light.png", projects, dark = false)

    @Test
    fun renderEmpty() = snapshot("home-empty.png", emptyList(), dark = true)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(name: String, projects: List<ProjectInfo>, dark: Boolean) {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
              CompositionLocalProvider(LocalAds provides fakeAdController(StubBackend())) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ProjectsHomeScreen(
                        projects = projects,
                        onOpen = {},
                        onNewProject = {},
                        onCloneRepository = {},
                        onExportProject = {},
                        onOpenHub = {},
                        onBackup = {},
                        storagePath = "/storage/emulated/0/CodeAssist",
                        onImportProject = {},
                    )
                }
              }
            }
        }
        try {
            scene.render()
            for (frame in 1..30) scene.render(frame * 50_000_000L)
            val img = scene.render(6_000_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 824
        const val HEIGHT = 1784
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
