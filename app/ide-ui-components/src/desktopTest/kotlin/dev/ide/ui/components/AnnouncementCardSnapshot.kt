package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAccent
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementButton
import dev.ide.ui.backend.UiAnnouncementButtonStyle
import dev.ide.ui.backend.UiAnnouncementStyle
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home's server-driven announcement card in its three styles, light and dark, at a 360 dp phone and a wide
 * window, and once right to left. Judged by eye; the assertion only catches a blank frame. Written to
 * `SNAPSHOT_DIR`, or the temporary folder.
 */
class AnnouncementCardSnapshot {

    private val banner = UiAnnouncement(
        id = "winter-sale", revision = 1, priority = 5, dismissible = true, endsAtMillis = null,
        style = UiAnnouncementStyle.BANNER, accent = UiAnnouncementAccent.AMBER, icon = "bolt", action = null,
        blocks = listOf(
            UiAnnouncementBlock.Title("Winter builds are in the store"),
            UiAnnouncementBlock.Buttons(listOf(UiAnnouncementButton("Browse", UiAnnouncementButtonStyle.PRIMARY, UiAnnouncementAction.StoreItem("winter")))),
        ),
    )

    private val card = UiAnnouncement(
        id = "plugins", revision = 2, priority = 3, dismissible = true, endsAtMillis = null,
        style = UiAnnouncementStyle.CARD, accent = UiAnnouncementAccent.TEAL, icon = "extension",
        action = UiAnnouncementAction.Screen("plugins"),
        blocks = listOf(
            UiAnnouncementBlock.Title("Plugins can add languages now"),
            UiAnnouncementBlock.Text("Install C# or Go support from the Plugins screen, then open any project that uses it."),
            UiAnnouncementBlock.Chips(listOf("New", "Free")),
            UiAnnouncementBlock.Buttons(
                listOf(
                    UiAnnouncementButton("Get plugins", UiAnnouncementButtonStyle.PRIMARY, UiAnnouncementAction.Screen("plugins")),
                    UiAnnouncementButton("Not now", UiAnnouncementButtonStyle.TEXT, UiAnnouncementAction.Dismiss),
                ),
            ),
        ),
    )

    private val hero = UiAnnouncement(
        id = "claude-launch", revision = 1, priority = 9, dismissible = true, endsAtMillis = null,
        style = UiAnnouncementStyle.HERO, accent = UiAnnouncementAccent.VIOLET, icon = null, action = null,
        blocks = listOf(
            UiAnnouncementBlock.Image("announce/claude-launch/cover.png", 2.4f, "Cover"),
            UiAnnouncementBlock.Title("Build with Claude"),
            UiAnnouncementBlock.Text("Connect Claude to your projects and let it read, edit and run them with you."),
            UiAnnouncementBlock.Divider(),
            UiAnnouncementBlock.Buttons(
                listOf(
                    UiAnnouncementButton("Set up", UiAnnouncementButtonStyle.PRIMARY, UiAnnouncementAction.Screen("agent.claude")),
                    UiAnnouncementButton("Learn more", UiAnnouncementButtonStyle.SECONDARY, UiAnnouncementAction.Url("https://example.com")),
                ),
            ),
        ),
    )

    private val arabic = card.copy(
        id = "ar", accent = UiAnnouncementAccent.LIME,
        blocks = listOf(
            UiAnnouncementBlock.Title("الإضافات تضيف لغات الآن"),
            UiAnnouncementBlock.Text("ثبّت دعم C# أو Go من شاشة الإضافات، ثم افتح أي مشروع يستخدمه."),
            UiAnnouncementBlock.Chips(listOf("جديد", "مجاني")),
            UiAnnouncementBlock.Buttons(listOf(UiAnnouncementButton("احصل على الإضافات", UiAnnouncementButtonStyle.PRIMARY, UiAnnouncementAction.Screen("plugins")))),
        ),
    )

    @Composable
    private fun all() {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnnouncementCard(banner, onAction = {}, onDismiss = {})
            AnnouncementCard(card, onAction = {}, onDismiss = {})
            AnnouncementCard(hero, onAction = {}, onDismiss = {}, loadImage = { cover() })
        }
    }

    @Test
    fun phoneLight() = render("announce-phone-light.png", 360, 900, dark = false) { all() }

    @Test
    fun phoneDark() = render("announce-phone-dark.png", 360, 900, dark = true) { all() }

    @Test
    fun wideLight() = render("announce-wide-light.png", 900, 900, dark = false) { all() }

    @Test
    fun heroWithoutImageDark() = render("announce-hero-noimage-dark.png", 360, 320, dark = true) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            AnnouncementCard(hero.copy(blocks = hero.blocks.drop(1)), onAction = {}, onDismiss = {})
        }
    }

    @Test
    fun rtlLight() = render("announce-rtl-light.png", 360, 420, dark = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnnouncementCard(arabic, onAction = {}, onDismiss = {})
                AnnouncementCard(banner.copy(blocks = listOf(UiAnnouncementBlock.Title("بناءات الشتاء في المتجر"))), onAction = {}, onDismiss = {})
            }
        }
    }

    /** A stand-in cover: a diagonal gradient, so cropping and corners are visible. */
    private fun cover(): ImageBitmap {
        val bmp = ImageBitmap(960, 400)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            Brush.linearGradient(listOf(Color(0xFF6E4CE0), Color(0xFFFFAED6)), Offset.Zero, Offset(960f, 400f))
                .applyTo(androidx.compose.ui.geometry.Size(960f, 400f), this, 1f)
        }
        canvas.drawRect(Rect(0f, 0f, 960f, 400f), paint)
        return bmp
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, wDp: Int, hDp: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark) { content() }
        }
        try {
            scene.render()
            for (frame in 1..10) scene.render(frame * 16_666_667L)
            val png = scene.render(400_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val out = File(System.getenv("SNAPSHOT_DIR") ?: File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").path)
                .apply { mkdirs() }
            File(out, name).writeBytes(png)
            assertTrue(png.size > 5_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }
}
