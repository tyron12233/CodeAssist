package dev.ide.store.impl

import dev.ide.store.RemoteItemKind
import dev.ide.store.StoreResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser tests over the **exact** JSON the validated `store_catalog()` / `store_search()` RPCs return.
 *
 * The fixture below was captured from a real local Supabase stack, not hand-written, so it carries the
 * quirks that matter: `rating` is absent rather than 0 when nothing is rated, `storagePath`/`sha256` are
 * absent for a template that routes through the Create-Project flow, and `jsonb_strip_nulls` has already
 * dropped every other null.
 */
class SupabaseStoreSourceTest {

    private val realCatalogJson = """
    {
      "version": 1,
      "generatedAt": "2026-09-01T07:12:03.412Z",
      "categories": [
        {"id":"java","title":"Java","summary":"Java apps, coursework and samples","icon":"coffee","count":1},
        {"id":"kotlin","title":"Kotlin","icon":"bolt","count":0}
      ],
      "featured": [
        {"id":"sample-calculator","kind":"sample","title":"Calculator",
         "summary":"A Java REPL that parses and evaluates expressions you type",
         "description":"Reads expressions from standard input.",
         "category":"java","language":"Java","tags":["java","parser","console"],
         "highlights":["Recursive-descent expression parser","Runs with no SDK or network"],
         "author":"CodeAssist","featured":true,"installs":0,"ratingCount":0,
         "templateId":"sample-calculator","updatedAt":"2026-09-01T06:39:56.982132+00:00"}
      ],
      "sections": [
        {"id":"templates","title":"Starter templates","summary":"Spin up a new project","items":[]},
        {"id":"samples","title":"Sample projects","items":[
          {"id":"alice-item-1","kind":"community","title":"Alice Item 1","summary":"sum",
           "category":"java","author":"Alice","authorHandle":"alice","verified":false,
           "installs":2,"rating":4.5,"ratingCount":612,
           "version":"1.0.5","versionCode":5,
           "storagePath":"29c43183/alice-item-1/v5.zip","sizeBytes":2048,
           "sha256":"0000000000000000000000000000000000000000000000000000000000000abc"}
        ]},
        {"id":"community","title":"Community","items":[]}
      ]
    }
    """.trimIndent()

    @Test
    fun parsesTheRealCatalogDocument() {
        val c = SupabaseStoreSource.parseCatalog(dev.ide.platform.JsonReader.parse(realCatalogJson))
        assertEquals(1, c.version)
        assertEquals("2026-09-01T07:12:03.412Z", c.generatedAt)
        assertEquals(listOf("java", "kotlin"), c.categories.map { it.id })
        assertEquals(1, c.categories.first().count)
        // Section ids must match the bundled catalog's, since that is the overlay join key.
        assertEquals(listOf("templates", "samples", "community"), c.sections.map { it.id })
        assertTrue(!c.isEmpty)
    }

    /** The seam that bit once already: unrated must be null, never 0.0. */
    @Test
    fun unratedItemHasNullRatingNotZero() {
        val c = SupabaseStoreSource.parseCatalog(dev.ide.platform.JsonReader.parse(realCatalogJson))
        val calculator = c.featured.single()
        assertNull(calculator.rating, "an item with no ratings must report null, not 0.0")
        assertEquals(0, calculator.ratingCount)
    }

    @Test
    fun templateItemHasNoPayloadAndKeepsItsTemplateId() {
        val calculator = SupabaseStoreSource.parseCatalog(dev.ide.platform.JsonReader.parse(realCatalogJson)).featured.single()
        assertEquals("sample-calculator", calculator.templateId)
        assertNull(calculator.storagePath)
        assertEquals(-1L, calculator.sizeBytes, "absent sizeBytes must stay negative, i.e. 'no payload'")
        assertEquals(RemoteItemKind.SAMPLE, calculator.kind)
    }

    @Test
    fun communityItemCarriesItsLatestVersionAndPayload() {
        val c = SupabaseStoreSource.parseCatalog(dev.ide.platform.JsonReader.parse(realCatalogJson))
        val item = c.sections.first { it.id == "samples" }.items.single()
        assertEquals(RemoteItemKind.COMMUNITY, item.kind)
        assertEquals("1.0.5", item.version)
        assertEquals(5, item.versionCode)
        assertEquals(2048L, item.sizeBytes)
        assertEquals(64, item.sha256?.length)
        assertEquals(4.5f, item.rating)
        assertEquals(612, item.ratingCount)
        assertEquals("alice", item.authorHandle)
    }

    /** A row missing an id or a title is dropped; the rest of the shelf still renders. */
    @Test
    fun malformedRowsAreDroppedNotFatal() {
        val json = """{"featured":[{"kind":"sample","title":"No id"},{"id":"x","kind":"sample","title":"Fine","summary":"s","category":"java"}]}"""
        val c = SupabaseStoreSource.parseCatalog(dev.ide.platform.JsonReader.parse(json))
        assertEquals(listOf("x"), c.featured.map { it.id })
    }

    @Test
    fun unknownKindFallsBackToCommunity() {
        assertEquals(RemoteItemKind.COMMUNITY, RemoteItemKind.of("something-new"))
        assertEquals(RemoteItemKind.COMMUNITY, RemoteItemKind.of(null))
        assertEquals(RemoteItemKind.TEMPLATE, RemoteItemKind.of("template"))
    }

    @Test
    fun unconfiguredSourceIsUnavailableRatherThanThrowing() {
        val s = SupabaseStoreSource(url = "", apiKey = "")
        assertTrue(!s.configured())
        assertTrue(s.catalog(84) is StoreResult.Unavailable)
        s.recordInstall("x", "y") // must not throw
    }

    /**
     * A query string reaches the server inside a hand-built JSON body, so a quote or a newline in what the
     * user typed must not be able to break out of it.
     */
    @Test
    fun jsonStringEscapingHandlesQuotesAndControlChars() {
        // jsonStr returns the value WITH its surrounding quotes.
        assertEquals("\"plain\"", SupabaseStoreSource.jsonStr("plain"))
        assertEquals("\"a\\\"b\"", SupabaseStoreSource.jsonStr("a\"b"))
        assertEquals("\"back\\\\slash\"", SupabaseStoreSource.jsonStr("back\\slash"))
        assertEquals("\"line\\nbreak\"", SupabaseStoreSource.jsonStr("line\nbreak"))
        assertEquals("\"tab\\there\"", SupabaseStoreSource.jsonStr("tab\there"))
        // And the escaped form must parse back to exactly what went in.
        val nasty = "quote\" brace} newline\n tab\t backslash\\"
        val roundTripped = dev.ide.platform.JsonReader.parse("{\"q\":" + SupabaseStoreSource.jsonStr(nasty) + "}")
        assertEquals(nasty, dev.ide.platform.JsonReader.str(roundTripped, "q"))
    }
}
