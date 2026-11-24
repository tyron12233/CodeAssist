package dev.ide.store.bridge

import dev.ide.store.RemoteCatalog
import dev.ide.store.RemoteStoreItem
import dev.ide.store.StoreCatalogSource
import dev.ide.store.StoreQuery
import dev.ide.store.StoreResult
import dev.ide.ui.backend.UiAnnouncementAction
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementStyle
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * When Home's announcements cost a request, and what is drawn in between.
 *
 * The request count is the point: Home is on screen at every launch, so anything looser than "a few times a
 * day per device" multiplies by every user. Each test counts the calls the source received.
 */
class AnnouncementCacheTest {

    private val dir = createTempDirectory("ca-announce-").toFile()
    private val file = File(dir, "store/home-announcements.json")
    private var clock = 1_800_000_000_000L
    private var signedIn = false

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun cache(source: StoreCatalogSource) =
        AnnouncementCache(source, cachePath = { file.absolutePath }, signedIn = { signedIn }, now = { clock })

    @Test
    fun aFreshAnswerIsNotAskedForAgain() = runTest {
        val source = Source(StoreResult.Ok(doc("hello")))
        val cache = cache(source)

        assertEquals(listOf("hello"), cache.announcements("en-US", allowNetwork = true).map { it.id })
        assertEquals(1, source.calls)
        clock += AnnouncementCache.FRESH_MS - 1
        cache.announcements("en-US", allowNetwork = true)
        cache.announcements("en-GB", allowNetwork = true)
        assertEquals(1, source.calls, "same language, same audience, inside the window: no request")

        clock += 2
        cache.announcements("en-US", allowNetwork = true)
        assertEquals(2, source.calls, "a stale answer is asked for again")
    }

    @Test
    fun withoutTheNetworkItNeverAsks() = runTest {
        val source = Source(StoreResult.Ok(doc("hello")))
        assertEquals(emptyList(), cache(source).announcements("en", allowNetwork = false))
        assertEquals(0, source.calls)
    }

    @Test
    fun aColdStartDrawsWhatWasKeptWithoutARequest() = runTest {
        val first = Source(StoreResult.Ok(doc("kept")))
        cache(first).announcements("es", allowNetwork = true)
        assertTrue(file.isFile, "the answer is kept on disk")

        clock += 60_000
        val second = Source(StoreResult.Ok(doc("new")))
        val restarted = cache(second)
        assertEquals(listOf("kept"), restarted.announcements("es", allowNetwork = false).map { it.id })
        assertEquals(listOf("kept"), restarted.announcements("es", allowNetwork = true).map { it.id })
        assertEquals(0, second.calls, "a cold start inside the window makes no request")

        clock += AnnouncementCache.FRESH_MS
        assertEquals(listOf("new"), restarted.announcements("es", allowNetwork = true).map { it.id })
        assertEquals(1, second.calls, "once at cold start when the kept answer is stale")
    }

    @Test
    fun anotherLanguageOrAudienceIsAnotherAnswer() = runTest {
        val source = Source(StoreResult.Ok(doc("x")))
        val cache = cache(source)
        cache.announcements("en", allowNetwork = true)
        cache.announcements("ar", allowNetwork = true)
        assertEquals(2, source.calls)
        assertEquals("ar", source.lastLocale)
        signedIn = true
        cache.announcements("ar", allowNetwork = true)
        assertEquals(3, source.calls)
        assertEquals(true, source.lastSignedIn)
        assertEquals(emptyList(), cache.announcements("fr", allowNetwork = false), "an answer for another language is not shown")
    }

    @Test
    fun anUnreachableServerIsRetriedSoonerThanARefusal() = runTest {
        val source = Source(StoreResult.Unavailable("offline"))
        val cache = cache(source)
        cache.announcements("en", allowNetwork = true)
        cache.announcements("en", allowNetwork = true)
        assertEquals(1, source.calls, "not on every visit")
        clock += AnnouncementCache.RETRY_MS
        cache.announcements("en", allowNetwork = true)
        assertEquals(2, source.calls, "retried after the short window")

        source.reply = StoreResult.Failed("function does not exist", 404)
        clock += AnnouncementCache.RETRY_MS
        cache.announcements("en", allowNetwork = true)
        assertEquals(3, source.calls)
        clock += AnnouncementCache.RETRY_MS
        cache.announcements("en", allowNetwork = true)
        assertEquals(3, source.calls, "a refusal waits the full window, like a success")
    }

    @Test
    fun aFailedRefreshKeepsTheLastAnswer() = runTest {
        val source = Source(StoreResult.Ok(doc("kept")))
        val cache = cache(source)
        cache.announcements("en", allowNetwork = true)
        source.reply = StoreResult.Unavailable("offline")
        clock += AnnouncementCache.FRESH_MS
        assertEquals(listOf("kept"), cache.announcements("en", allowNetwork = true).map { it.id })
        source.reply = StoreResult.Ok("garbage")
        clock += AnnouncementCache.FRESH_MS
        assertEquals(listOf("kept"), cache.announcements("en", allowNetwork = true).map { it.id })
    }

    @Test
    fun anExpiredAnnouncementDisappearsBeforeTheNextRefresh() = runTest {
        val ends = java.time.Instant.ofEpochMilli(clock + 60_000).toString()
        val source = Source(StoreResult.Ok(doc("short", endsAt = ends)))
        val cache = cache(source)
        assertEquals(1, cache.announcements("en", allowNetwork = true).size)
        clock += 60_000
        assertEquals(0, cache.announcements("en", allowNetwork = true).size)
        assertEquals(1, source.calls, "no request was needed to drop it")
    }

    @Test
    fun mapsForTheUiInTheCallersLanguage() = runTest {
        val source = Source(StoreResult.Ok(doc("loc", title = """{"en":"Hello","pt-BR":"Olá"}""")))
        val a = cache(source).announcements("pt-BR", allowNetwork = true).single()
        assertEquals(UiAnnouncementStyle.CARD, a.style)
        assertEquals("loc@4", a.dismissKey)
        assertEquals(UiAnnouncementBlock.Title("Olá"), a.blocks.first())
        assertEquals(UiAnnouncementAction.StoreItem("snow"), a.action)
    }

    @Test
    fun anUnconfiguredStoreAsksNothing() = runTest {
        val source = Source(StoreResult.Ok(doc("x")), configured = false)
        assertEquals(emptyList(), cache(source).announcements("en", allowNetwork = true))
        assertEquals(0, source.calls)
    }

    @Test
    fun theKeyIsLanguageAndAudience() {
        assertEquals("pt|1", AnnouncementCache.keyFor("pt_BR", signedIn = true))
        assertEquals("en|0", AnnouncementCache.keyFor("EN-us", signedIn = false))
    }

    private fun doc(id: String, endsAt: String? = null, title: String = "\"Hi\""): String =
        """{"version":1,"announcements":[{"id":"$id","revision":4,"priority":1,"dismissible":true,""" +
            (if (endsAt != null) """"endsAt":"$endsAt",""" else "") +
            """"layout":{"style":"card","action":{"type":"store_item","id":"snow"},"blocks":[{"type":"title","text":$title}]}}]}"""

    private class Source(
        var reply: StoreResult<String>,
        private val configured: Boolean = true,
    ) : StoreCatalogSource {
        var calls = 0
        var lastLocale: String? = null
        var lastSignedIn: Boolean? = null

        override fun configured() = configured
        override fun homeAnnouncementsDocument(locale: String, signedIn: Boolean): StoreResult<String> {
            calls++
            lastLocale = locale
            lastSignedIn = signedIn
            return reply
        }
        override fun catalog(appBuild: Int) = StoreResult.Unavailable<RemoteCatalog>("unused")
        override fun search(query: StoreQuery, appBuild: Int) = StoreResult.Unavailable<List<RemoteStoreItem>>("unused")
        override fun recordInstall(slug: String, installId: String) = Unit
    }
}
