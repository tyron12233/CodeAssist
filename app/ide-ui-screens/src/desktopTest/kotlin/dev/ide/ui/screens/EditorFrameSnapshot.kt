package dev.ide.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.ide.ui.ToolWindowIds
import dev.ide.ui.ToolWindowSide
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.assertEquals
import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.theme.CodeAssistTheme
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.ToolWindowRegistry

/**
 * Off-screen renders of the whole wide editor layout (top bar, tool-window stripes, panes, console), for
 * eyeballing how the window chrome fits together. Not an assertion.
 */
class EditorFrameSnapshot {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun renderWideLayout() {
        render("editor-frame-desktop.png", 1280, 760, dark = true) { it.consoleOpen = true }
        render("editor-frame-desktop-light.png", 1280, 760, dark = false) { it.consoleOpen = false; it.selectLeftPanel("search") }
        render("editor-frame-landscape.png", 860, 390, dark = true) { it.consoleOpen = false; it.selectLeftPanel("structure") }
        render("editor-frame-phone-drawer.png", 400, 820, dark = true, compact = true) { it.consoleOpen = false; it.selectLeftPanel("files") }
        // A RIGHT tool window gets a bottom-nav tab on the phone, which has no right stripe.
        val ai = ToolWindowRegistry.register(ToolWindowContribution("test.ai", "AI", "sparkle", ToolWindowAnchor.RIGHT) {})
        try {
            render("editor-frame-phone.png", 400, 820, dark = true, compact = true) { it.consoleOpen = false; it.selectedLeftPanel = null }
        } finally {
            ai.dispose()
        }
    }

    /** The Notifications tool window: open on the right stripe (its count on the button), and closed. */
    @Test
    fun renderNotificationsToolWindow() {
        val now = System.currentTimeMillis()
        val items = listOf(
            dev.ide.ui.backend.UiNotification(
                id = "p", kind = dev.ide.ui.backend.UiNotificationKind.SYSTEM, source = "Hello Plugin", count = 2,
                title = "Hello Plugin ran its packaged tool",
                body = "Hello from a binary the plugin packaged, running as /data/app/com.example.hello/lib/arm64/libhellotool.so",
                timestampMs = now - 6 * 60_000,
            ),
            dev.ide.ui.backend.UiNotification(
                id = "b", kind = dev.ide.ui.backend.UiNotificationKind.BUILD, level = dev.ide.ui.backend.UiNotificationLevel.ERROR,
                title = "Build failed", body = "2 errors in :app", timestampMs = now - 30 * 60_000,
            ),
            dev.ide.ui.backend.UiNotification(
                id = "s", kind = dev.ide.ui.backend.UiNotificationKind.STORE_SUBMISSION, title = "Aurora Notes is live in the store",
                body = "Approved by a moderator", timestampMs = now - 3 * 3_600_000, read = true,
            ),
        )
        val backend = object : StubBackend() {
            override val notifications = object : dev.ide.ui.backend.NotificationService {
                override fun notifications() = kotlinx.coroutines.flow.MutableStateFlow(items)
                override fun unreadCount() = kotlinx.coroutines.flow.MutableStateFlow(2)
            }
        }
        val ai = ToolWindowRegistry.register(ToolWindowContribution("test.ai", "AI", "sparkle", ToolWindowAnchor.RIGHT) {})
        val tw = ToolWindowRegistry.register(
            ToolWindowContribution(
                NOTIFICATIONS_TOOL_WINDOW, "Notifications", "bell", ToolWindowAnchor.RIGHT, order = 2000,
                badge = { 2 },
            ) { ctx -> NotificationsPanel(ctx.backend) {} },
        )
        try {
            render("editor-frame-notifications.png", 1280, 760, dark = true, backend = backend) {
                it.consoleOpen = false; it.showToolWindow(NOTIFICATIONS_TOOL_WINDOW)
            }
            render("editor-frame-notifications-closed.png", 1280, 760, dark = true, backend = backend) {
                it.consoleOpen = false; it.selectLeftPanel("files")
            }
        } finally {
            tw.dispose(); ai.dispose()
        }
    }

    /** Tool windows moved off their natural side, a phone in landscape (860x390dp) with the build console
     *  beside the editor, below it dragged taller, and maximized, and zen mode. */
    @Test
    fun renderMovedToolWindowsAndZen() {
        render("editor-frame-desktop-moved.png", 1280, 760, dark = true) {
            it.moveToolWindow("search", ToolWindowSide.Bottom)
            it.moveToolWindow(ToolWindowIds.BUILD, ToolWindowSide.Right)
            it.selectLeftPanel("files")
        }
        render("editor-frame-landscape-console-right.png", 860, 390, dark = true) {
            it.moveToolWindow(ToolWindowIds.BUILD, ToolWindowSide.Right)
        }
        render("editor-frame-landscape-console-bottom.png", 860, 390, dark = true) {
            it.bottomPaneHeight = 220f; it.consoleOpen = true
        }
        render("editor-frame-landscape-console-max.png", 860, 390, dark = true) {
            it.moveToolWindow(ToolWindowIds.BUILD, ToolWindowSide.Left); it.maximizedSide = ToolWindowSide.Left
        }
        render("editor-frame-landscape-zen.png", 860, 390, dark = true) {
            it.open("/project/app/src/Main.java", "Main.java"); it.setZen(true)
        }
    }

    /** Dragging the Files stripe button over the editor's right-hand target docks Files on the right. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun draggingAStripeButtonMovesTheToolWindow() {
        val state = IdeUiState(StubBackend(), mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = 1280 * 2, height = 760 * 2, density = Density(2f)) {
            CodeAssistTheme(dark = true) { expanded(state) }
        }
        try {
            scene.render()
            // The first stripe button (Files), just under the top bar, in px at density 2.
            val start = Offset(20f * 2, 78f * 2)
            scene.sendPointerEvent(PointerEventType.Press, start)
            var p = start
            var t = 0L
            repeat(30) {
                p += Offset(36f * 2, 4f * 2)
                t += 16_000_000L
                scene.sendPointerEvent(PointerEventType.Move, p)
                scene.render(t)
            }
            val png = scene.render(t + 16_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/editor-frame-dragging.png").apply { parentFile?.mkdirs() }.writeBytes(png)
            scene.sendPointerEvent(PointerEventType.Release, p)
            scene.render(t + 32_000_000L)
            assertEquals(ToolWindowSide.Right, state.sideOf("files"))
            assertEquals("files", state.shownOn(ToolWindowSide.Right))
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        name: String, wDp: Int, hDp: Int, dark: Boolean, compact: Boolean = false,
        backend: dev.ide.ui.backend.IdeBackend = StubBackend(), setup: (IdeUiState) -> Unit,
    ) {
        val state = IdeUiState(backend, mainDispatcher = Dispatchers.Unconfined, ioDispatcher = Dispatchers.Unconfined)
        setup(state)
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                if (compact) CompactLayout(
                    state = state,
                    onToggleTheme = {}, onOpenHub = {}, onOpenIconManager = {},
                    indexStatus = IndexUiStatus(), buildState = BuildState(),
                    onNewFile = { _, _ -> }, onNewFolder = { _, _ -> }, onNewResource = {}, onNewImageAsset = {},
                    onNewSource = { _, _, _ -> }, onFileOp = { },
                    onOpenDependencies = {}, onOpenModuleConfig = {}, onCloseProject = {},
                    fileActions = FileActions.None,
                ) else expanded(state)
            }
        }
        try {
            scene.render()
            var t = 0L
            repeat(20) { t += 32_000_000L; scene.render(t) }
            val png = scene.render(t + 500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots/$name").apply { parentFile?.mkdirs() }
            out.writeBytes(png)
            println("wrote snapshot: $out")
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun expanded(state: IdeUiState) = ExpandedLayout(
        state = state,
        onToggleTheme = {}, onOpenHub = {}, onOpenIconManager = {},
        indexStatus = IndexUiStatus(), buildState = BuildState(),
        onNewFile = { _, _ -> }, onNewFolder = { _, _ -> }, onNewResource = {}, onNewImageAsset = {},
        onNewSource = { _, _, _ -> }, onFileOp = { },
        onOpenDependencies = {}, onOpenModuleConfig = {}, onCloseProject = {},
        fileActions = FileActions.None,
    )
}
