package dev.ide.store.impl

import dev.ide.platform.JsonReader
import dev.ide.store.AnnouncementAction
import dev.ide.store.AnnouncementAlign
import dev.ide.store.AnnouncementArrange
import dev.ide.store.AnnouncementAt
import dev.ide.store.AnnouncementBlock
import dev.ide.store.AnnouncementButtonStyle
import dev.ide.store.AnnouncementColor
import dev.ide.store.AnnouncementFit
import dev.ide.store.AnnouncementFontWeight
import dev.ide.store.AnnouncementHideOn
import dev.ide.store.AnnouncementLayout
import dev.ide.store.AnnouncementPaint
import dev.ide.store.AnnouncementVAlign
import dev.ide.store.AnnouncementWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Layout version 2: the design, colours, paints, common block properties, the new blocks, and the limits. */
class AnnouncementLayoutV2Test {

    private fun layout(json: String): AnnouncementLayout = AnnouncementParser.parseLayout(JsonReader.parse(json))!!
    private fun v2(blocks: String, extra: String = ""): AnnouncementLayout = layout("""{"v":2$extra,"blocks":$blocks}""")
    private fun color(json: String, auto: Boolean = false) = AnnouncementParser.parseColor(JsonReader.parse(json), auto)
    private fun paint(json: String) = AnnouncementParser.parsePaint(JsonReader.parse(json))

    @Test
    fun theContractExampleParses() {
        val l = layout(CONTRACT_EXAMPLE)
        assertEquals(2, l.version)
        val d = l.design!!
        val bg = assertIs<AnnouncementPaint.Linear>(d.background)
        assertEquals(135f, bg.angle)
        assertEquals(listOf(0xFF3B1E8FL, 0xFF0E7C86L), bg.colors.map { (it as AnnouncementColor.Fixed).light })
        assertEquals(AnnouncementColor.Fixed(0xFFFFFFFFL, 0xFFFFFFFFL), d.content)
        assertEquals(32f, d.corner)
        assertEquals(20f, d.padding)

        val row = assertIs<AnnouncementBlock.Row>(l.blocks[0])
        assertEquals(12f, row.gap)
        assertEquals(AnnouncementVAlign.CENTER, row.valign)
        val icon = assertIs<AnnouncementBlock.Icon>(row.children[0])
        assertEquals("sparkle", icon.name)
        assertEquals(28f, icon.size)
        assertEquals(14f, icon.props.corner)
        assertEquals(listOf(10f, 10f, 10f, 10f), icon.props.pad)
        // "#FFFFFF22" is #RRGGBBAA: white at 0x22 alpha.
        assertEquals(AnnouncementPaint.Solid(AnnouncementColor.Fixed(0x22FFFFFFL, 0x22FFFFFFL)), icon.props.background)
        val column = assertIs<AnnouncementBlock.Column>(row.children[1])
        assertEquals(1f, column.props.weight)
        assertEquals(2f, column.gap)
        assertIs<AnnouncementBlock.Badge>(column.children[0])
        assertEquals(22f, assertIs<AnnouncementBlock.Title>(column.children[1]).style.size, "xl is 22sp")

        val buttons = assertIs<AnnouncementBlock.Buttons>(l.blocks[2])
        assertEquals(AnnouncementArrange.START, buttons.arrange)
        assertEquals(AnnouncementButtonStyle.PRIMARY, buttons.items[0].style)
        assertEquals(AnnouncementColor.Fixed(0xFFFFFFFFL, 0xFFFFFFFFL), buttons.items[0].color)
        assertEquals(AnnouncementColor.Fixed(0xFF3B1E8FL, 0xFF3B1E8FL), buttons.items[0].content)
        assertEquals(AnnouncementAction.Dismiss, buttons.items[1].action)
    }

    @Test
    fun colorFormats() {
        assertEquals(AnnouncementColor.Fixed(0xFF112233L, 0xFF112233L), color("\"#112233\""))
        assertEquals(AnnouncementColor.Fixed(0x80112233L, 0x80112233L), color("\"#11223380\""), "alpha last")
        assertEquals(AnnouncementColor.Fixed(0xCCFFFFFFL, 0xCCFFFFFFL), color("\"#FFFFFFCC\""))
        assertEquals(AnnouncementColor.Fixed(0x00000000L, 0x00000000L), color("\"#00000000\""))
        assertEquals(AnnouncementColor.Fixed(0xFFAABBCCL, 0xFFAABBCCL), color("\"#aabbcc\""))
        assertEquals(AnnouncementColor.Fixed(0xFF000000L, 0xFFFFFFFFL), color("""{"light":"#000000","dark":"#FFFFFF"}"""))
        assertEquals(AnnouncementColor.Fixed(0xFF000000L, 0xFF000000L), color("""{"light":"#000000"}"""), "one side stands for both")
        assertEquals(AnnouncementColor.Token("primaryContainer"), color("\"primaryContainer\""))
        assertEquals(AnnouncementColor.Token("onAccent"), color("\"onAccent\""))
        assertEquals(AnnouncementColor.Auto, color("\"auto\"", auto = true))
        assertNull(color("\"auto\""), "auto only where it is documented")
        for (bad in listOf("\"red\"", "\"#12345\"", "\"#GGHHII\"", "\"#+1223344\"", "\"112233\"", "\"#1122334455\"", "12", "{}", """{"light":"nope"}""")) {
            assertNull(color(bad), bad)
        }
    }

    @Test
    fun paints() {
        assertEquals(AnnouncementPaint.Solid(AnnouncementColor.Token("surface")), paint("\"surface\""))
        assertIs<AnnouncementPaint.Solid>(paint("""{"light":"#FFFFFF","dark":"#000000"}"""))
        val linear = assertIs<AnnouncementPaint.Linear>(paint("""{"gradient":"linear","colors":["#000000","#111111","#222222","#333333","#444444","#555555"],"angle":400}"""))
        assertEquals(5, linear.colors.size, "at most five colours")
        assertEquals(360f, linear.angle, "angle clamped")
        assertEquals(0f, assertIs<AnnouncementPaint.Linear>(paint("""{"gradient":"linear","colors":["#000000","#111111"]}""")).angle)
        assertNull(paint("""{"gradient":"linear","colors":["#000000"]}"""), "one colour is not a gradient")
        assertNull(paint("""{"gradient":"linear","colors":["#000000","nope"]}"""), "an invalid colour does not count")
        assertNull(paint("""{"gradient":"conic","colors":["#000000","#111111"]}"""))
        val radial = assertIs<AnnouncementPaint.Radial>(paint("""{"gradient":"radial","colors":["primary","#00000000"],"center":[1.5,-1],"radius":9}"""))
        assertEquals(1f, radial.centerX)
        assertEquals(0f, radial.centerY)
        assertEquals(2f, radial.radius)
        val defaults = assertIs<AnnouncementPaint.Radial>(paint("""{"gradient":"radial","colors":["#000000","#FFFFFF"],"center":[0.2]}"""))
        assertEquals(0.5f, defaults.centerX, "a malformed centre is ignored")
        assertEquals(AnnouncementParser.DEFAULT_RADIAL_RADIUS, defaults.radius)
        val image = assertIs<AnnouncementPaint.Image>(paint("""{"image":"announce/x/bg.png","fit":"contain","scrim":2,"scrimColor":"#102030"}"""))
        assertEquals(AnnouncementFit.CONTAIN, image.fit)
        assertEquals(0.9f, image.scrim)
        assertEquals(AnnouncementColor.Fixed(0xFF102030L, 0xFF102030L), image.scrimColor)
        val plain = assertIs<AnnouncementPaint.Image>(paint("""{"image":"https://cdn.example.com/a.png"}"""))
        assertEquals(AnnouncementFit.COVER, plain.fit)
        assertEquals(0f, plain.scrim)
        assertNull(paint("""{"image":"http://cdn.example.com/a.png"}"""))
        assertNull(paint("\"auto\""), "auto is not a background")
    }

    @Test
    fun designIsClampedAndDefaultsStayNull() {
        val d = layout("""{"v":2,"design":{"corner":99,"padding":-4,"minHeight":9999,"elevation":20,"fullBleed":true,
            "border":{"color":"outline","width":10},"content":"auto","unknown":1},"blocks":[{"type":"title","text":"T"}]}""").design!!
        assertEquals(48f, d.corner)
        assertEquals(0f, d.padding)
        assertEquals(480f, d.minHeight)
        assertEquals(8f, d.elevation)
        assertEquals(true, d.fullBleed)
        assertEquals(4f, d.border!!.width)
        assertEquals(AnnouncementColor.Token("outline"), d.border!!.color)
        assertEquals(AnnouncementColor.Auto, d.content)
        val empty = layout("""{"v":2,"design":{},"blocks":[{"type":"title","text":"T"}]}""").design!!
        assertNull(empty.background); assertNull(empty.corner); assertNull(empty.padding); assertNull(empty.border)
        assertNull(layout("""{"v":2,"design":{"border":{"width":2}},"blocks":[]}""").design!!.border, "a border needs a colour")
    }

    @Test
    fun commonBlockProperties() {
        val t = assertIs<AnnouncementBlock.Text>(
            v2("""[{"type":"text","text":"T","pad":[1,2,3,99],"align":"center","width":"wrap","weight":50,
                 "background":"#000000","corner":60,"hideOn":"phone","at":"bottomEnd","bogus":true}]""").blocks.single(),
        )
        assertEquals(listOf(1f, 2f, 3f, 48f), t.props.pad)
        assertEquals(AnnouncementAlign.CENTER, t.props.align)
        assertEquals(AnnouncementWidth.WRAP, t.props.width)
        assertEquals(10f, t.props.weight)
        assertEquals(48f, t.props.corner)
        assertEquals(AnnouncementHideOn.PHONE, t.props.hideOn)
        assertEquals(AnnouncementAt.BOTTOM_END, t.props.at)
        assertEquals(listOf(5f, 5f, 5f, 5f), AnnouncementParser.parsePad(5L))
        assertNull(AnnouncementParser.parsePad(listOf(1L, 2L)), "only a number or four sides")
        val plain = assertIs<AnnouncementBlock.Text>(v2("""[{"type":"text","text":"T","align":"left","width":"half","hideOn":"tablet"}]""").blocks.single())
        assertSame(dev.ide.store.AnnouncementProps.NONE, plain.props, "invalid values leave the defaults")
    }

    @Test
    fun textStyle() {
        val t = assertIs<AnnouncementBlock.Title>(
            v2("""[{"type":"title","text":"T","size":"xxl","fontWeight":"semibold","color":"auto","textAlign":"end","maxLines":40,"italic":true}]""").blocks.single(),
        )
        assertEquals(28f, t.style.size)
        assertEquals(AnnouncementFontWeight.SEMIBOLD, t.style.weight)
        assertEquals(AnnouncementColor.Auto, t.style.color)
        assertEquals(AnnouncementAlign.END, t.style.textAlign)
        assertEquals(10, t.style.maxLines)
        assertEquals(true, t.style.italic)
        val legacy = assertIs<AnnouncementBlock.Text>(v2("""[{"type":"text","text":"T","weight":"bold"}]""").blocks.single())
        assertNull(legacy.style.weight, "a string weight is not a font weight")
        assertNull(legacy.props.weight, "nor a row share")
        val share = assertIs<AnnouncementBlock.Text>(v2("""[{"type":"text","text":"T","weight":2,"fontWeight":"medium"}]""").blocks.single())
        assertEquals(2f, share.props.weight)
        assertEquals(AnnouncementFontWeight.MEDIUM, share.style.weight)
        val sizes = v2("""[{"type":"text","text":"a","size":4},{"type":"text","text":"b","size":100},{"type":"text","text":"c","size":"huge"},{"type":"text","text":"d","size":"xs"}]""")
            .blocks.map { (it as AnnouncementBlock.Text).style.size }
        assertEquals(listOf(10f, 48f, null, 11f), sizes)
    }

    @Test
    fun newBlocks() {
        val blocks = v2(
            """[
              {"type":"icon","name":"rocket","size":200,"color":"accent"},
              {"type":"icon","name":"unicorn"},
              {"type":"badge","text":"NEW","color":"#FFFFFF","background":"error"},
              {"type":"chips","items":[{"text":"a"}],"color":"onSurface","background":"surfaceVariant"},
              {"type":"buttons","arrange":"stacked","items":[{"label":"Go","action":{"type":"dismiss"},"color":"#000000","content":"auto","fullWidth":true,"icon":"bolt"},
                                                            {"label":"Bad icon","action":{"type":"dismiss"},"icon":"unicorn"}]},
              {"type":"buttons","arrange":"spaceBetween","items":[{"label":"x","action":{"type":"dismiss"}}]},
              {"type":"divider","color":"outline","thickness":10},
              {"type":"spacer","size":100},
              {"type":"spacer"},
              {"type":"image","src":"announce/a/b.png","height":1000,"fit":"contain","corner":20}
            ]""",
        ).blocks
        val icon = assertIs<AnnouncementBlock.Icon>(blocks[0])
        assertEquals(96f, icon.size)
        assertEquals(AnnouncementColor.Token("accent"), icon.color)
        val badge = assertIs<AnnouncementBlock.Badge>(blocks[1])
        assertEquals(AnnouncementPaint.Solid(AnnouncementColor.Token("error")), badge.props.background)
        assertEquals(AnnouncementColor.Token("onSurface"), assertIs<AnnouncementBlock.Chips>(blocks[2]).color)
        val stacked = assertIs<AnnouncementBlock.Buttons>(blocks[3])
        assertEquals(AnnouncementArrange.STACKED, stacked.arrange)
        assertEquals(true, stacked.items[0].fullWidth)
        assertEquals("bolt", stacked.items[0].icon)
        assertEquals(AnnouncementColor.Auto, stacked.items[0].content)
        assertNull(stacked.items[1].icon)
        assertNull(assertIs<AnnouncementBlock.Buttons>(blocks[4]).arrange, "spaceBetween is a row's, not a button row's")
        val divider = assertIs<AnnouncementBlock.Divider>(blocks[5])
        assertEquals(4f, divider.thickness)
        assertEquals(64f, assertIs<AnnouncementBlock.Spacer>(blocks[6]).size)
        assertNull(assertIs<AnnouncementBlock.Spacer>(blocks[7]).size)
        val image = assertIs<AnnouncementBlock.Image>(blocks[8])
        assertEquals(400f, image.height)
        assertEquals(AnnouncementFit.CONTAIN, image.fit)
        assertEquals(20f, image.props.corner)
    }

    @Test
    fun containers() {
        val l = v2(
            """[
              {"type":"row","gap":99,"valign":"bottom","arrange":"spaceBetween","children":[{"type":"text","text":"a","weight":2},{"type":"text","text":"b"}]},
              {"type":"stack","children":[{"type":"image","src":"announce/a/b.png"},{"type":"badge","text":"NEW","at":"topEnd"}]},
              {"type":"row","children":[]},
              {"type":"column","children":[{"type":"mystery"}]}
            ]""",
        )
        assertEquals(2, l.blocks.size, "empty containers are dropped")
        val row = assertIs<AnnouncementBlock.Row>(l.blocks[0])
        assertEquals(32f, row.gap)
        assertEquals(AnnouncementVAlign.BOTTOM, row.valign)
        assertEquals(AnnouncementArrange.SPACE_BETWEEN, row.arrange)
        assertEquals(2f, row.children[0].props.weight)
        val stack = assertIs<AnnouncementBlock.Stack>(l.blocks[1])
        assertEquals(AnnouncementAt.TOP_END, stack.children[1].props.at)
    }

    @Test
    fun nestingDeeperThanFourIsDropped() {
        fun nest(levels: Int): String =
            if (levels == 0) """{"type":"text","text":"leaf"}""" else """{"type":"column","children":[${nest(levels - 1)}]}"""
        fun depth(b: AnnouncementBlock): Int = when (b) {
            is AnnouncementBlock.Column -> 1 + (b.children.maxOfOrNull(::depth) ?: 0)
            else -> 0
        }
        assertEquals(4, depth(v2("[${nest(4)}]").blocks.single()), "four containers deep is allowed")
        // The fifth container is skipped, which leaves the fourth empty, and so on up: nothing survives.
        assertTrue(v2("[${nest(5)}]").blocks.isEmpty())
        val mixed = v2("""[{"type":"row","children":[{"type":"text","text":"keep"},${nest(4)}]}]""").blocks.single() as AnnouncementBlock.Row
        assertEquals(1, mixed.children.size, "the too-deep branch goes, its sibling stays")
    }

    @Test
    fun atMostFortyBlocksCountingNested() {
        val texts = (1..50).joinToString(",") { """{"type":"text","text":"t$it"}""" }
        assertEquals(40, v2("[$texts]").blocks.size)
        val nested = """[{"type":"column","children":[${(1..45).joinToString(",") { """{"type":"text","text":"n$it"}""" }}]},{"type":"title","text":"late"}]"""
        val l = v2(nested)
        val column = assertIs<AnnouncementBlock.Column>(l.blocks.single())
        assertEquals(39, column.children.size, "the column counts as one of the forty")
    }

    @Test
    fun aVersionOneLayoutIgnoresDesign() {
        val l = layout("""{"style":"card","design":{"corner":4},"blocks":[{"type":"title","text":"T"}]}""")
        assertEquals(1, l.version)
        assertNull(l.design)
        assertEquals(1, layout("""{"v":3,"blocks":[]}""").version, "an unknown version reads as the subset")
    }

    @Test
    fun aDesignedCardWithOnlyABackgroundStillCounts() {
        val feed = AnnouncementParser.parse("""{"announcements":[{"id":"bg","layout":{"v":2,"design":{"background":"#000000"},"blocks":[{"type":"row","children":[{"type":"badge","text":"Hi"}]}]}}]}""")!!
        assertEquals(listOf("bg"), feed.announcements.map { it.id })
    }

    companion object {
        val CONTRACT_EXAMPLE = """
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
        """.trimIndent()
    }
}
