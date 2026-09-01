package dev.ide.core.backend

import dev.ide.ui.backend.UiNotification
import dev.ide.ui.backend.UiNotificationKind
import dev.ide.ui.backend.UiNotificationTarget
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The notification center.
 *
 * Two behaviours carry the design and both fail quietly if broken: notifications survive a restart (the
 * ones worth having are the ones the user was absent for), and a keyed post replaces rather than stacks
 * (otherwise polling turns the list into a log nobody reads).
 */
class NotificationCenterTest {

    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = temps.forEach { it.deleteRecursively() }

    private fun dir(): File = kotlin.io.path.createTempDirectory("ca-notif-").toFile().also { temps += it }

    private var clock = 1_000L
    private fun center(root: File?, presenter: ((UiNotification) -> Unit)? = null) =
        NotificationCenter(root, now = { clock += 10; clock }, presenter = presenter)

    @Test
    fun postingShowsUpAndCountsAsUnread() {
        val c = center(dir())
        c.post(UiNotificationKind.BUILD, "Build failed", "2 errors in :app")

        assertEquals(1, c.notifications().value.size)
        assertEquals(1, c.unreadCount().value)
        val n = c.notifications().value.single()
        assertEquals("Build failed", n.title)
        assertEquals("2 errors in :app", n.body)
        assertFalse(n.read)
        assertTrue(n.timestampMs > 0, "the engine owns the clock, so a timestamp must be set")
    }

    @Test
    fun newestIsFirst() {
        val c = center(dir())
        c.post(UiNotificationKind.SYSTEM, "First")
        c.post(UiNotificationKind.SYSTEM, "Second")
        assertEquals(listOf("Second", "First"), c.notifications().value.map { it.title })
    }

    /** The rule that keeps this a list of facts rather than a log of polls. */
    @Test
    fun aKeyedPostReplacesTheEarlierOneInsteadOfStacking() {
        val c = center(dir())
        c.post(UiNotificationKind.STORE_UPDATE, "Update available for Jetsnack", key = "update:jetsnack")
        c.post(UiNotificationKind.STORE_UPDATE, "Update available for Jetsnack (1.2)", key = "update:jetsnack")
        c.post(UiNotificationKind.STORE_UPDATE, "Update available for Nordlys", key = "update:nordlys")

        assertEquals(2, c.notifications().value.size, "one entry per fact: ${c.notifications().value.map { it.title }}")
        assertEquals("Update available for Nordlys", c.notifications().value.first().title)
        assertEquals("Update available for Jetsnack (1.2)", c.notifications().value.last().title)
    }

    /** A fact becoming true again is news again, even though it is the same entry. */
    @Test
    fun repostingAKeyMakesItUnreadAgain() {
        val c = center(dir())
        val first = c.post(UiNotificationKind.STORE_SUBMISSION, "In review", key = "sub:1")
        c.markRead(first.id)
        assertEquals(0, c.unreadCount().value)

        c.post(UiNotificationKind.STORE_SUBMISSION, "Approved", key = "sub:1")
        assertEquals(1, c.unreadCount().value)
        assertEquals(1, c.notifications().value.size)
        assertEquals("Approved", c.notifications().value.single().title)
    }

    @Test
    fun keylessPostsAreAlwaysDistinct() {
        val c = center(dir())
        c.post(UiNotificationKind.BUILD, "Build failed")
        c.post(UiNotificationKind.BUILD, "Build failed")
        assertEquals(2, c.notifications().value.size, "two builds failing are two events")
    }

    @Test
    fun readingDismissingAndClearing() {
        val c = center(dir())
        val a = c.post(UiNotificationKind.SYSTEM, "A")
        c.post(UiNotificationKind.SYSTEM, "B")
        assertEquals(2, c.unreadCount().value)

        c.markRead(a.id)
        assertEquals(1, c.unreadCount().value)
        c.markAllRead()
        assertEquals(0, c.unreadCount().value)
        assertTrue(c.notifications().value.all { it.read })

        c.dismiss(a.id)
        assertEquals(listOf("B"), c.notifications().value.map { it.title })
        c.clearAll()
        assertEquals(emptyList(), c.notifications().value)
        assertEquals(0, c.unreadCount().value)
    }

    /** The whole reason this is a file: the interesting notifications arrive while the app is closed. */
    @Test
    fun notificationsSurviveARestart() {
        val root = dir()
        val first = center(root)
        first.post(
            kind = UiNotificationKind.STORE_SUBMISSION,
            title = "Jetsnack is live in the store",
            body = "Approved by a moderator",
            target = UiNotificationTarget.StoreItem("jetsnack"),
            key = "submission:jetsnack:1.0.0",
        )
        val read = first.post(UiNotificationKind.BUILD, "Build finished")
        first.markRead(read.id)

        val reopened = center(root)
        assertEquals(2, reopened.notifications().value.size)
        assertEquals(1, reopened.unreadCount().value, "read state has to persist too")
        val restored = assertNotNull(reopened.notifications().value.firstOrNull { it.key == "submission:jetsnack:1.0.0" })
        assertEquals("Jetsnack is live in the store", restored.title)
        assertEquals("Approved by a moderator", restored.body)
        assertEquals(UiNotificationTarget.StoreItem("jetsnack"), restored.target, "the tap target must survive")
        assertFalse(restored.read)
    }

    @Test
    fun everyTargetKindRoundTrips() {
        val root = dir()
        val c = center(root)
        c.post(UiNotificationKind.SYSTEM, "item", target = UiNotificationTarget.StoreItem("slug-1"), key = "a")
        c.post(UiNotificationKind.SYSTEM, "project", target = UiNotificationTarget.Project("/projects/x"), key = "b")
        c.post(UiNotificationKind.SYSTEM, "subs", target = UiNotificationTarget.Submissions, key = "c")
        c.post(UiNotificationKind.SYSTEM, "screen", target = UiNotificationTarget.Screen("Storage"), key = "d")
        c.post(UiNotificationKind.SYSTEM, "none", key = "e")

        val byKey = center(root).notifications().value.associateBy { it.key }
        assertEquals(UiNotificationTarget.StoreItem("slug-1"), byKey["a"]?.target)
        assertEquals(UiNotificationTarget.Project("/projects/x"), byKey["b"]?.target)
        assertEquals(UiNotificationTarget.Submissions, byKey["c"]?.target)
        assertEquals(UiNotificationTarget.Screen("Storage"), byKey["d"]?.target)
        assertEquals(null, byKey["e"]?.target)
    }

    /** Titles are user and server text, so the writer has to survive whatever is in them. */
    @Test
    fun awkwardTextSurvivesTheRoundTrip() {
        val root = dir()
        val nasty = """He said "hello" \ then a newline:
and a tab:	end"""
        center(root).post(UiNotificationKind.SYSTEM, nasty, body = "back\\slash", key = "x")
        assertEquals(nasty, center(root).notifications().value.single().title)
        assertEquals("back\\slash", center(root).notifications().value.single().body)
    }

    /** A newer build's kind must not cost the user a notification they have never seen. */
    @Test
    fun anUnknownKindLoadsAsSystemRatherThanDisappearing() {
        val root = dir()
        File(root, "notifications.json").writeText(
            """{"version":1,"notifications":[{"id":"n1","kind":"TELEPATHY","title":"From the future","read":false,"timestampMs":5}]}""",
        )
        val loaded = center(root).notifications().value.single()
        assertEquals(UiNotificationKind.SYSTEM, loaded.kind)
        assertEquals("From the future", loaded.title)
    }

    @Test
    fun theListIsCappedSoTheFileStaysSmall() {
        val c = center(dir())
        repeat(140) { c.post(UiNotificationKind.BUILD, "Build $it") }
        assertEquals(100, c.notifications().value.size)
        assertEquals("Build 139", c.notifications().value.first().title, "the cap drops the oldest, not the newest")
    }

    @Test
    fun aHostPresenterIsToldAboutEachNewNotification() {
        val seen = mutableListOf<String>()
        val c = center(dir()) { seen += it.title }
        c.post(UiNotificationKind.BUILD, "Build failed")
        c.post(UiNotificationKind.SYSTEM, "Welcome")
        assertEquals(listOf("Build failed", "Welcome"), seen)
    }

    /** A presenter that throws is the host's problem and must not lose the notification. */
    @Test
    fun aFailingPresenterDoesNotLoseTheNotification() {
        val c = center(dir()) { error("no notification permission") }
        c.post(UiNotificationKind.BUILD, "Build failed")
        assertEquals(1, c.notifications().value.size)
    }

    // ---- adopt(): notifications the host built while no engine existed (the push path) ----

    @Test
    fun adoptedPushesKeepTheirOwnArrivalTimeAndOrdering() {
        val c = center(dir())
        c.post(UiNotificationKind.BUILD, "Built this morning")   // clock-based, newest so far
        val lastNight = UiNotification(
            id = "p1", kind = UiNotificationKind.STORE_SUBMISSION, title = "Approved overnight",
            timestampMs = 5L, key = "submission:x:1.0.0",
        )
        c.adopt(listOf(lastNight))

        val titles = c.notifications().value.map { it.title }
        assertEquals(listOf("Built this morning", "Approved overnight"), titles,
            "an overnight push belongs below this morning's entry, not on top because it was adopted later")
        assertEquals(5L, c.notifications().value.last().timestampMs, "the arrival time must not be re-stamped")
        assertEquals(2, c.unreadCount().value)
    }

    /** The tray already showed it; adopting must not produce a second copy of the same fact. */
    @Test
    fun adoptingReplacesAnEntryWithTheSameKey() {
        val c = center(dir())
        c.post(UiNotificationKind.STORE_SUBMISSION, "In review", key = "submission:x:1.0.0")
        c.adopt(
            listOf(
                UiNotification(
                    id = "submission:x:1.0.0", kind = UiNotificationKind.STORE_SUBMISSION,
                    title = "Approved", timestampMs = 9_000L, key = "submission:x:1.0.0",
                ),
            ),
        )
        assertEquals(1, c.notifications().value.size)
        assertEquals("Approved", c.notifications().value.single().title)
    }

    @Test
    fun adoptedPushesPersistLikeAnyOther() {
        val root = dir()
        center(root).adopt(
            listOf(
                UiNotification(
                    id = "p9", kind = UiNotificationKind.STORE_SUBMISSION, title = "Approved while closed",
                    body = "Looks good", timestampMs = 1234L,
                    target = UiNotificationTarget.StoreItem("aurora"), key = "submission:aurora:2.0.0",
                ),
            ),
        )
        val reopened = center(root).notifications().value.single()
        assertEquals("Approved while closed", reopened.title)
        assertEquals(UiNotificationTarget.StoreItem("aurora"), reopened.target)
        assertEquals(1234L, reopened.timestampMs)
        assertFalse(reopened.read)
    }

    @Test
    fun adoptingNothingIsANoOp() {
        val c = center(dir())
        c.post(UiNotificationKind.SYSTEM, "A")
        val before = c.notifications().value
        c.adopt(emptyList())
        assertEquals(before, c.notifications().value)
    }

    /** A backlog bigger than the cap must not push the cap open. */
    @Test
    fun adoptingRespectsTheCap() {
        val c = center(dir())
        repeat(80) { c.post(UiNotificationKind.BUILD, "Build $it") }
        c.adopt((1..60).map {
            UiNotification(id = "p$it", kind = UiNotificationKind.SYSTEM, title = "Push $it", timestampMs = 900_000L + it)
        })
        assertEquals(100, c.notifications().value.size)
    }

    /** No storage is a real host configuration (tests, a desktop with no home), not an error. */
    @Test
    fun worksWithNoStorageAtAll() {
        val c = center(null)
        c.post(UiNotificationKind.SYSTEM, "Ephemeral")
        assertEquals(1, c.notifications().value.size)
        assertEquals(1, c.unreadCount().value)
    }
}
