package dev.ide.store.impl

import dev.ide.store.AnnouncementAccent
import dev.ide.store.AnnouncementAction
import dev.ide.store.AnnouncementBlock
import dev.ide.store.AnnouncementButtonStyle
import dev.ide.store.AnnouncementStyle
import dev.ide.store.AnnouncementText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `home_announcements(...)` document as the app reads it: every block and action type, text in several
 * languages, and the parts an older app must skip rather than fail on.
 */
class AnnouncementParserTest {

    private fun one(layout: String, extra: String = ""): dev.ide.store.Announcement? =
        AnnouncementParser.parse("""{"version":1,"announcements":[{"id":"a","revision":2$extra,"layout":$layout}]}""")
            ?.announcements?.singleOrNull()

    @Test
    fun readsEveryBlockAndActionType() {
        val doc = """
        {"version":1,"announcements":[{
          "id":"claude-launch","revision":3,"priority":10,"dismissible":false,"endsAt":"2026-12-01T00:00:00+00:00",
          "layout":{
            "style":"hero","accent":"teal","icon":"rocket",
            "action":{"type":"screen","id":"agent.claude"},
            "blocks":[
              {"type":"image","src":"announce/claude-launch/cover.png","aspect":2.0,"alt":"Claude and the IDE"},
              {"type":"title","text":"Use with Claude"},
              {"type":"text","text":{"en":"Connect it","es":"Conéctalo"}},
              {"type":"chips","items":[{"text":"New"},{"text":"Free"}]},
              {"type":"buttons","items":[
                {"label":"Set up","style":"primary","action":{"type":"screen","id":"agent.claude"}},
                {"label":"Docs","style":"secondary","action":{"type":"url","url":"https://example.com/docs"}},
                {"label":"Store","style":"text","action":{"type":"store_item","id":"snow-globe"}},
                {"label":"Settings","action":{"type":"settings","id":"editor"}},
                {"label":"Learn","action":{"type":"learn","id":"kotlin-basics"}},
                {"label":"Later","action":{"type":"dismiss"}}
              ]},
              {"type":"divider"},
              {"type":"spacer"}
            ]
          }
        }]}
        """.trimIndent()
        val feed = AnnouncementParser.parse(doc)!!
        val a = feed.announcements.single()
        assertEquals("claude-launch", a.id)
        assertEquals(3, a.revision)
        assertEquals(10, a.priority)
        assertEquals(false, a.dismissible)
        assertEquals("2026-12-01T00:00:00+00:00", a.endsAt)
        assertEquals(AnnouncementStyle.HERO, a.layout.style)
        assertEquals(AnnouncementAccent.TEAL, a.layout.accent)
        assertEquals("rocket", a.layout.icon)
        assertEquals(AnnouncementAction.Screen("agent.claude"), a.layout.action)

        val blocks = a.layout.blocks
        assertEquals(7, blocks.size)
        val image = assertIs<AnnouncementBlock.Image>(blocks[0])
        assertEquals("announce/claude-launch/cover.png", image.src)
        assertEquals(2.0f, image.aspect)
        assertEquals("Claude and the IDE", image.alt?.resolve("en"))
        assertEquals("Use with Claude", assertIs<AnnouncementBlock.Title>(blocks[1]).text.resolve("fr"))
        assertEquals("Conéctalo", assertIs<AnnouncementBlock.Text>(blocks[2]).text.resolve("es-MX"))
        assertEquals(listOf("New", "Free"), assertIs<AnnouncementBlock.Chips>(blocks[3]).items.map { it.resolve("en") })

        val buttons = assertIs<AnnouncementBlock.Buttons>(blocks[4]).items
        assertEquals(
            listOf(
                AnnouncementAction.Screen("agent.claude"),
                AnnouncementAction.Url("https://example.com/docs"),
                AnnouncementAction.StoreItem("snow-globe"),
                AnnouncementAction.Settings("editor"),
                AnnouncementAction.Learn("kotlin-basics"),
                AnnouncementAction.Dismiss,
            ),
            buttons.map { it.action },
        )
        assertEquals(
            listOf(
                AnnouncementButtonStyle.PRIMARY, AnnouncementButtonStyle.SECONDARY, AnnouncementButtonStyle.TEXT,
                AnnouncementButtonStyle.SECONDARY, AnnouncementButtonStyle.SECONDARY, AnnouncementButtonStyle.SECONDARY,
            ),
            buttons.map { it.style },
        )
        assertIs<AnnouncementBlock.Divider>(blocks[5])
        assertIs<AnnouncementBlock.Spacer>(blocks[6])
        assertEquals(1, a.layout.version, "no \"v\" is version 1")
        assertNull(a.layout.design)
    }

    @Test
    fun textFallsBackFromTagToLanguageToEnglishToFirst() {
        val t = AnnouncementText(mapOf("es" to "hola", "pt-BR" to "olá", "en" to "hello", "zh" to "你好"))
        assertEquals("olá", t.resolve("pt-BR"), "an exact tag wins")
        assertEquals("olá", t.resolve("pt_br"), "tags compare case-insensitively, with _ as -")
        assertEquals("olá", t.resolve("pt-PT"), "a regional variant stands in for its language")
        assertEquals("hola", t.resolve("es-419"), "the language of a regional tag")
        assertEquals("hello", t.resolve("ar"), "English when the language is missing")
        assertEquals("hola", AnnouncementText(mapOf("es" to "hola", "fr" to "salut")).resolve("ar"), "the first value last")
        assertEquals("plain", AnnouncementText.plain("plain").resolve("ar-EG"), "a plain string for everyone")
    }

    @Test
    fun skipsUnknownBlocksAndDropsButtonsWithUnknownActions() {
        val a = one(
            """{"blocks":[
                 {"type":"title","text":"Hi"},
                 {"type":"carousel","items":[]},
                 {"type":"buttons","items":[
                   {"label":"Go","action":{"type":"teleport","id":"x"}},
                   {"label":"Keep","action":{"type":"store_item","id":"y"}},
                   {"label":"No id","action":{"type":"screen"}},
                   {"action":{"type":"dismiss"}}
                 ]},
                 {"type":"buttons","items":[{"label":"Only bad","action":{"type":"nope"}}]},
                 {"type":"chips","items":[{"text":""},{"nottext":"x"}]},
                 "not an object"
               ]}""",
        )!!
        assertEquals(2, a.layout.blocks.size, "the unknown block, the empty button row and the empty chips are gone")
        val buttons = assertIs<AnnouncementBlock.Buttons>(a.layout.blocks[1]).items
        assertEquals(listOf("Keep"), buttons.map { it.label.resolve("en") })
    }

    @Test
    fun onlyHttpsLeavesTheApp() {
        assertNull(AnnouncementParser.parseAction(json("""{"type":"url","url":"http://example.com"}""")))
        assertNull(AnnouncementParser.parseAction(json("""{"type":"url","url":"javascript:alert(1)"}""")))
        assertNull(AnnouncementParser.parseAction(json("""{"type":"url","url":"intent://x"}""")))
        assertNull(AnnouncementParser.parseAction(json("""{"type":"url"}""")))
        assertEquals(
            AnnouncementAction.Url("https://example.com/a"),
            AnnouncementParser.parseAction(json("""{"type":"url","url":" https://example.com/a "}""")),
        )

        assertTrue(AnnouncementParser.isAllowedImage("announce/x/cover.png"))
        assertTrue(AnnouncementParser.isAllowedImage("https://cdn.example.com/x.png"))
        assertTrue(!AnnouncementParser.isAllowedImage("http://cdn.example.com/x.png"))
        assertTrue(!AnnouncementParser.isAllowedImage("file:///etc/passwd"))
        assertTrue(!AnnouncementParser.isAllowedImage("/abs/x.png"))
        assertTrue(!AnnouncementParser.isAllowedImage("announce/../x.png"))
        assertTrue(!AnnouncementParser.isAllowedImage("data:image/png;base64,AAAA"))

        val a = one("""{"blocks":[{"type":"title","text":"T"},{"type":"image","src":"http://x/y.png"}],"action":{"type":"url","url":"http://x"}}""")!!
        assertEquals(1, a.layout.blocks.size, "an http image is dropped")
        assertNull(a.layout.action, "an http body action is dropped, leaving the body inert")
    }

    @Test
    fun unknownStyleAccentAndIconFallBack() {
        val a = one("""{"style":"poster","accent":"chartreuse","icon":"unicorn","blocks":[{"type":"title","text":"T"}]}""")!!
        assertEquals(AnnouncementStyle.CARD, a.layout.style)
        assertEquals(AnnouncementAccent.NEUTRAL, a.layout.accent)
        assertNull(a.layout.icon)
        val b = one("""{"style":"BANNER","accent":"Lime","blocks":[{"type":"text","text":"T"}]}""")!!
        assertEquals(AnnouncementStyle.BANNER, b.layout.style)
        assertEquals(AnnouncementAccent.LIME, b.layout.accent)
    }

    @Test
    fun imageAspectIsClamped() {
        val a = one("""{"blocks":[{"type":"image","src":"announce/a/x.png","aspect":40},{"type":"image","src":"announce/a/y.png"}]}""")!!
        assertEquals(4f, assertIs<AnnouncementBlock.Image>(a.layout.blocks[0]).aspect)
        assertEquals(AnnouncementParser.DEFAULT_ASPECT, assertIs<AnnouncementBlock.Image>(a.layout.blocks[1]).aspect)
    }

    @Test
    fun dropsAnnouncementsWithNothingToDraw() {
        assertNull(one("""{"blocks":[{"type":"divider"},{"type":"buttons","items":[{"label":"x","action":{"type":"dismiss"}}]}]}"""))
        assertNull(one("""{"blocks":"nope"}"""))
        assertNull(one(""""not an object""""))
        val feed = AnnouncementParser.parse("""{"announcements":[{"layout":{"blocks":[{"type":"title","text":"no id"}]}},{"id":"ok","layout":{"blocks":[{"type":"title","text":"T"}]}}]}""")!!
        assertEquals(listOf("ok"), feed.announcements.map { it.id })
        assertEquals(1, feed.announcements.single().revision, "revision defaults to 1")
        assertEquals(true, feed.announcements.single().dismissible, "dismissible defaults to true")
    }

    @Test
    fun malformedDocumentsAreNotAnError() {
        assertNull(AnnouncementParser.parse("not json"))
        assertNull(AnnouncementParser.parse("[]"))
        assertEquals(0, AnnouncementParser.parse("""{"version":1}""")!!.announcements.size)
    }

    private fun json(s: String): Any? = dev.ide.platform.JsonReader.parse(s)
}
