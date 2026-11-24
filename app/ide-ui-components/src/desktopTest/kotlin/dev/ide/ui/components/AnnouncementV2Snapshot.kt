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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ide.store.bridge.AnnouncementMapper
import dev.ide.store.impl.AnnouncementParser
import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.theme.CodeAssistTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Layout version 2, rendered from JSON through the real parser and mapper: the contract's example, a radial
 * banner, an image hero with a scrim, a row/column/stack composition with `hideOn`, and a light/dark colour pair,
 * at a 360 dp phone and a wide window, light, dark and right to left. Each layout is also written out as
 * `example-*.json` beside the pictures. Judged by eye; the assertion only catches a blank frame.
 */
class AnnouncementV2Snapshot {

    private fun parse(id: String, layout: String, tag: String = "en"): UiAnnouncement {
        val doc = """{"version":1,"announcements":[{"id":"$id","revision":1,"dismissible":true,"layout":$layout}]}"""
        val feed = AnnouncementParser.parse(doc) ?: error("$id did not parse")
        return AnnouncementMapper.toUi(feed, tag, 0L).single()
    }

    @Test
    fun contractPhoneLight() = render("v2-contract-phone-light.png", 360, 300, dark = false) { card(parse("ex", CONTRACT)) }

    @Test
    fun contractPhoneDark() = render("v2-contract-phone-dark.png", 360, 300, dark = true) { card(parse("ex", CONTRACT)) }

    @Test
    fun contractWide() = render("v2-contract-wide-light.png", 900, 260, dark = false, wide = true) { card(parse("ex", CONTRACT)) }

    @Test
    fun contractRtl() = render("v2-contract-rtl-light.png", 360, 300, dark = false, rtl = true) { card(parse("ex", CONTRACT_AR, tag = "ar")) }

    @Test
    fun radialBannerPhone() = render("v2-radial-banner-phone-dark.png", 360, 160, dark = true) { card(parse("banner", RADIAL_BANNER)) }

    @Test
    fun radialBannerWide() = render("v2-radial-banner-wide-light.png", 900, 140, dark = false, wide = true) { card(parse("banner", RADIAL_BANNER)) }

    @Test
    fun imageHeroPhone() = render("v2-image-hero-phone-light.png", 360, 420, dark = false) { card(parse("hero", IMAGE_HERO)) }

    @Test
    fun imageHeroWide() = render("v2-image-hero-wide-dark.png", 900, 360, dark = true, wide = true) { card(parse("hero", IMAGE_HERO)) }

    @Test
    fun compositionPhone() = render("v2-composition-phone-light.png", 360, 460, dark = false) { card(parse("comp", COMPOSITION)) }

    @Test
    fun compositionWide() = render("v2-composition-wide-light.png", 900, 580, dark = false, wide = true) { card(parse("comp", COMPOSITION)) }

    @Test
    fun colorPairLight() = render("v2-color-pair-phone-light.png", 360, 260, dark = false) { card(parse("pair", COLOR_PAIR)) }

    @Test
    fun colorPairDark() = render("v2-color-pair-phone-dark.png", 360, 260, dark = true) { card(parse("pair", COLOR_PAIR)) }

    @Test
    fun writesTheExamples() {
        val out = outDir()
        mapOf(
            "example-contract.json" to CONTRACT,
            "example-radial-banner.json" to RADIAL_BANNER,
            "example-image-hero.json" to IMAGE_HERO,
            "example-composition.json" to COMPOSITION,
            "example-color-pair.json" to COLOR_PAIR,
        ).forEach { (name, json) ->
            parse(name.removeSuffix(".json"), json)
            File(out, name).writeText(json.trimIndent() + "\n")
        }
    }

    @Composable
    private fun card(a: UiAnnouncement) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnnouncementCard(a, onAction = {}, onDismiss = {}, loadImage = { photo() })
        }
    }

    /** A stand-in photograph: a warm-to-dark diagonal with a bright patch, so fit, cropping and scrim show. */
    private fun photo(): ImageBitmap {
        val bmp = ImageBitmap(1200, 600)
        val canvas = Canvas(bmp)
        val size = Size(1200f, 600f)
        val paint = Paint()
        Brush.linearGradient(listOf(Color(0xFFFFC46B), Color(0xFFE0566E), Color(0xFF2B1D4F)), Offset.Zero, Offset(1200f, 600f))
            .applyTo(size, paint, 1f)
        canvas.drawRect(Rect(0f, 0f, 1200f, 600f), paint)
        val sun = Paint()
        Brush.radialGradient(listOf(Color(0xFFFFFFFF), Color(0x00FFFFFF)), Offset(900f, 160f), 220f).applyTo(size, sun, 1f)
        canvas.drawRect(Rect(0f, 0f, 1200f, 600f), sun)
        return bmp
    }

    private fun outDir(): File =
        File(System.getenv("SNAPSHOT_DIR") ?: File(System.getProperty("java.io.tmpdir"), "codeassist-snapshots").path).apply { mkdirs() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        name: String,
        wDp: Int,
        hDp: Int,
        dark: Boolean,
        wide: Boolean = false,
        rtl: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width = wDp * 2, height = hDp * 2, density = Density(2f)) {
            CodeAssistTheme(dark = dark) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalAnnouncementHost provides AnnouncementHost(bleed = 16.dp, wide = wide),
                ) { content() }
            }
        }
        try {
            scene.render()
            for (frame in 1..10) scene.render(frame * 16_666_667L)
            val png = scene.render(400_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(outDir(), name).writeBytes(png)
            assertTrue(png.size > 5_000, "$name should render more than a blank frame")
        } finally {
            scene.close()
        }
    }

    companion object {
        /** The contract's example, verbatim. */
        val CONTRACT = """
        {
          "v": 2, "style": "card", "accent": "violet",
          "design": { "background": {"gradient":"linear","colors":["#3B1E8F","#0E7C86"],"angle":135},
                      "content": "#FFFFFF", "corner": 32, "padding": 20 },
          "blocks": [
            {"type":"row","gap":12,"valign":"center","children":[
              {"type":"icon","name":"sparkle","size":28,"background":"#FFFFFF22","corner":14,"pad":10},
              {"type":"column","weight":1,"gap":2,"children":[
                {"type":"badge","text":"NEW","background":"#FFFFFF","color":"#3B1E8F"},
                {"type":"title","text":{"en":"Claude in your IDE"},"size":"xl"}
              ]}
            ]},
            {"type":"text","text":{"en":"Connect claude.ai to the project you have open."},"color":"#FFFFFFCC"},
            {"type":"buttons","arrange":"start","items":[
              {"label":{"en":"Set up"},"style":"primary","color":"#FFFFFF","content":"#3B1E8F","action":{"type":"screen","id":"agent.claude"}},
              {"label":{"en":"Later"},"style":"text","content":"#FFFFFF","action":{"type":"dismiss"}}
            ]}
          ]
        }
        """

        /** The example in Arabic, for right to left (the gradient's start flips to the right). */
        val CONTRACT_AR = CONTRACT
            .replace("""{"en":"Claude in your IDE"}""", """{"en":"Claude in your IDE","ar":"Claude في بيئة التطوير"}""")
            .replace("""{"en":"Connect claude.ai to the project you have open."}""", """{"en":"x","ar":"اربط claude.ai بالمشروع المفتوح لديك."}""")
            .replace("""{"en":"Set up"}""", """{"en":"Set up","ar":"إعداد"}""")
            .replace("""{"en":"Later"}""", """{"en":"Later","ar":"لاحقًا"}""")
            .replace(""""text":"NEW"""", """"text":"جديد"""")

        val RADIAL_BANNER = """
        {
          "v": 2, "style": "banner", "accent": "amber", "icon": "fire",
          "design": { "background": {"gradient":"radial","colors":["#FFB347","#E0566E","#3A1240"],"center":[0.15,0.2],"radius":1.1},
                      "fullBleed": true, "padding": 14 },
          "blocks": [
            {"type":"row","gap":10,"valign":"center","children":[
              {"type":"title","text":{"en":"Winter builds are live"},"maxLines":2,"weight":1},
              {"type":"buttons","items":[{"label":{"en":"Browse"},"style":"primary","color":"#FFFFFF","content":"#3A1240","icon":"store","action":{"type":"store_item","id":"winter"}}]}
            ]}
          ]
        }
        """

        val IMAGE_HERO = """
        {
          "v": 2, "style": "hero", "accent": "teal",
          "design": { "background": {"image":"announce/hero/cover.png","fit":"cover","scrim":0.55,"scrimColor":"#000000"},
                      "minHeight": 260, "corner": 28, "padding": 20, "elevation": 3 },
          "blocks": [
            {"type":"spacer","size":56},
            {"type":"badge","text":{"en":"EVENT"},"background":"accent","color":"onAccent"},
            {"type":"title","text":{"en":"Daily challenge: Advent of Kotlin"},"size":"xxl"},
            {"type":"text","text":{"en":"Twenty-four small puzzles, one a day, solved on your phone."},"maxLines":2},
            {"type":"buttons","arrange":"spaced","items":[
              {"label":{"en":"Start today"},"style":"primary","action":{"type":"learn","id":"advent"}},
              {"label":{"en":"Rules"},"style":"secondary","action":{"type":"url","url":"https://example.com/rules"}}
            ]}
          ]
        }
        """

        val COMPOSITION = """
        {
          "v": 2, "style": "card", "accent": "lime",
          "design": { "background": "surface", "border": {"color":"outline","width":1}, "corner": 24, "padding": 16 },
          "blocks": [
            {"type":"stack","children":[
              {"type":"image","src":"announce/comp/shot.png","aspect":2.2,"corner":18},
              {"type":"badge","text":{"en":"PLUGIN"},"at":"topEnd","pad":[4,10,4,10],"corner":10,"background":"#000000E6","color":"#FFFFFF"},
              {"type":"text","text":{"en":"Go support"},"at":"bottomStart","pad":[6,10,6,10],"background":"#00000099","corner":12,"color":"#FFFFFF","fontWeight":"semibold","width":"wrap"}
            ]},
            {"type":"row","gap":12,"valign":"top","children":[
              {"type":"icon","name":"extension","size":24,"color":"onAccent","background":"accent","corner":12,"pad":8},
              {"type":"column","weight":1,"gap":4,"children":[
                {"type":"title","text":{"en":"Languages are plugins now"}},
                {"type":"text","text":{"en":"C#, Go and Rust install from the Plugins screen."},"size":"s","color":"onSurfaceVariant"},
                {"type":"chips","items":[{"text":"C#"},{"text":"Go"},{"text":"Rust"}]}
              ]}
            ]},
            {"type":"text","text":{"en":"Only on a phone: tap Get plugins."},"hideOn":"wide","size":"xs","italic":true,"color":"onSurfaceVariant"},
            {"type":"text","text":{"en":"Only on a wide window: the plugin list opens beside your projects."},"hideOn":"phone","size":"xs","italic":true,"color":"onSurfaceVariant"},
            {"type":"divider","thickness":1},
            {"type":"row","arrange":"spaceBetween","valign":"center","children":[
              {"type":"text","text":{"en":"Free, open source"},"size":"s","fontWeight":"medium","width":"wrap"},
              {"type":"buttons","items":[{"label":{"en":"Get plugins"},"style":"primary","icon":"download","action":{"type":"screen","id":"plugins"}}]}
            ]}
          ]
        }
        """

        val COLOR_PAIR = """
        {
          "v": 2, "style": "card", "accent": "neutral", "icon": "info",
          "design": { "background": {"light":"#FFF4D6","dark":"#2E2410"},
                      "content": {"light":"#3A2A00","dark":"#FFE7A8"},
                      "border": {"color":{"light":"#E8C66A","dark":"#6B5520"},"width":1.5} },
          "blocks": [
            {"type":"title","text":{"en":"Scheduled maintenance"}},
            {"type":"text","text":{"en":"Store downloads may be slow on Sunday between 02:00 and 03:00 UTC."}},
            {"type":"buttons","items":[
              {"label":{"en":"Details"},"style":"secondary","color":{"light":"#F5DD9A","dark":"#4A3A14"},"content":"auto","action":{"type":"url","url":"https://example.com/status"}},
              {"label":{"en":"Got it"},"style":"text","action":{"type":"dismiss"}}
            ]}
          ]
        }
        """
    }
}
