package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.UiToolchainWarning
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders [ToolchainWarningBanner] off-screen at a PHONE width and at a desktop width, so the compact layout
 * (full-width stacked actions, collapsed explanation) can be eyeballed and, more importantly, so a layout that
 * would throw or clip at 430dp fails here instead of on a device.
 *
 * The compact case is the one that regressed before: a fix pill, a "Build anyway" pill and a caveat sentence all
 * on one row are unreadable at phone width, so `compact` stacks them.
 */
class ToolchainWarningBannerSnapshot {

    private val hilt = UiToolchainWarning(
        id = "ksp-runtime:hilt",
        moduleName = "data",
        title = "data: Hilt / Dagger runtime is out of step",
        detail = "The bundled Hilt / Dagger processor generates code referencing dagger.internal.Provider, " +
            "which data's runtime does not provide. The IDE always runs the processor version it bundles, so " +
            "the runtime has to match.",
        fixLabel = "Update hilt-android to 2.60.1",
    )

    private val room = hilt.copy(
        id = "ksp-runtime:room",
        moduleName = "persistence",
        title = "persistence: Room runtime is out of step",
        detail = "The bundled Room processor generates code referencing androidx.room.util.SQLiteStatementUtil, " +
            "which persistence's runtime does not provide. The IDE always runs the processor version it bundles, " +
            "so the runtime has to match.",
        fixLabel = "Downgrade room-runtime to 2.8.4",
    )

    private inner class FakeBackend(private val warnings: List<UiToolchainWarning>) : StubBackend() {
        override suspend fun toolchainWarnings(): List<UiToolchainWarning> = warnings
    }

    @Test
    fun rendersOnAPhoneWidth() {
        // 860x900 @ density 2 = 430x450dp: a realistic phone width, below the two-pane breakpoint.
        snapshot("toolchain-warning-phone.png", 860, 900, compact = true, warnings = listOf(hilt))
    }

    @Test
    fun rendersTwoModulesOnAPhoneWidth() {
        // Project-wide now, so several modules can be listed at once; the strip must stay bounded.
        snapshot("toolchain-warning-phone-two.png", 860, 1100, compact = true, warnings = listOf(hilt, room))
    }

    /** The collapsed summary must actually open: a click on the row expands to the per-module cards. */
    @Test
    fun theCollapsedSummaryExpandsToTheCards() {
        snapshot(
            "toolchain-warning-phone-two-open.png", 860, 1300, compact = true,
            warnings = listOf(hilt, room),
            // Mid-row, left of the caret/close buttons: the summary row itself is the toggle.
            clickAt = Offset(200f, 50f),
        )
    }

    @Test
    fun rendersOnADesktopWidth() {
        snapshot("toolchain-warning-desktop.png", 2200, 600, compact = false, warnings = listOf(hilt))
    }

    /** Nothing to say means nothing drawn: the strip must not reserve space on a healthy project. */
    @Test
    fun drawsNothingWhenThereAreNoWarnings() {
        snapshot("toolchain-warning-none.png", 860, 300, compact = true, warnings = emptyList())
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun snapshot(
        name: String,
        w: Int,
        h: Int,
        compact: Boolean,
        warnings: List<UiToolchainWarning>,
        clickAt: Offset? = null,
    ) {
        val state = IdeUiState(FakeBackend(warnings))
        val scene = ImageComposeScene(width = w, height = h, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.fillMaxWidth()) { ToolchainWarningBanner(state, compact) }
                }
            }
        }
        try {
            // Pump frames so the toolchainWarnings LaunchedEffect resolves before the capture.
            scene.render()
            scene.render(50_000_000L)
            scene.render(150_000_000L)
            clickAt?.let { at ->
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                // Enough frames for the expand animation to finish, else the capture catches it mid-height.
                var t = 200_000_000L
                repeat(20) { scene.render(t); t += 100_000_000L }
            }
            val img = scene.render(3_000_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            assertTrue(png.isNotEmpty(), "$name produced no image")
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val OUT_DIR = "build/snapshots"
    }
}
