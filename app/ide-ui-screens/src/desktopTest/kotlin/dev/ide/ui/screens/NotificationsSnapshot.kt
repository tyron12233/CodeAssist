package dev.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ide.ui.StubBackend
import dev.ide.ui.backend.NotificationService
import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind
import dev.ide.ui.backend.UiNotificationTarget
import dev.ide.ui.theme.CodeAssistTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat

/**
 * The notification bell and its badge, rendered off-screen.
 *
 * The badge is the interesting part: it is read from `unreadCount()` rather than the list, and it caps at
 * "9+", so a regression there is silent — a wrong or missing number looks like an ordinary icon. Rendering
 * three counts side by side makes it visible.
 */
class NotificationsSnapshot {

    /** A backend whose notification center is fixed data, so the render is deterministic. */
    private class FakeNotifications(
        private val items: List<UiNotification>,
        private val unread: Int,
    ) : NotificationService {
        override fun notifications(): StateFlow<List<UiNotification>> = MutableStateFlow(items)
        override fun unreadCount(): StateFlow<Int> = MutableStateFlow(unread)
    }

    private class NotifyingBackend(items: List<UiNotification>, unread: Int) : StubBackend() {
        override val notifications: NotificationService = FakeNotifications(items, unread)
    }

    private val sample = listOf(
        UiNotification(
            id = "1",
            kind = UiNotificationKind.STORE_SUBMISSION,
            title = "Aurora Notes is live in the store",
            body = "Approved by a moderator",
            timestampMs = System.currentTimeMillis() - 4 * 60_000,
            target = UiNotificationTarget.StoreItem("aurora-notes"),
            key = "submission:aurora-notes:1.0.0",
        ),
        UiNotification(
            id = "2",
            kind = UiNotificationKind.BUILD,
            title = "Build failed",
            body = "2 errors in :app",
            timestampMs = System.currentTimeMillis() - 3 * 3_600_000,
            read = true,
        ),
    )

    @Test
    fun renderBellCounts() {
        val png = render("notifications-bell.png") {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // No badge at all, a plain count, and the cap.
                NotificationBell(NotifyingBackend(emptyList(), 0), onClick = {})
                NotificationBell(NotifyingBackend(sample, 3), onClick = {})
                NotificationBell(NotifyingBackend(sample, 14), onClick = {})
            }
        }
        assertTrue(png > 0, "the bell row should render to a non-empty image")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, content: @androidx.compose.runtime.Composable () -> Unit): Int {
        val scene = ImageComposeScene(width = 480, height = 200, density = Density(2f)) {
            CodeAssistTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column { content() }
                }
            }
        }
        return try {
            scene.render()
            val img = scene.render(1_000_000_000L)
            val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("$OUT_DIR/$name").apply { parentFile?.mkdirs() }.writeBytes(png)
            println("wrote snapshot: $OUT_DIR/$name (${png.size} bytes)")
            png.size
        } finally {
            scene.close()
        }
    }

    private companion object {
        val OUT_DIR: String = File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").absolutePath
    }
}
