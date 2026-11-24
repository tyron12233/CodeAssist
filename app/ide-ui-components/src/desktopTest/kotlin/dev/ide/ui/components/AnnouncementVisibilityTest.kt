package dev.ide.ui.components

import dev.ide.ui.backend.UiAnnouncement
import dev.ide.ui.backend.UiAnnouncementAccent
import dev.ide.ui.backend.UiAnnouncementBlock
import dev.ide.ui.backend.UiAnnouncementStyle
import dev.ide.ui.backend.UiFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which served announcements Home draws, and how a dismissal is remembered. */
class AnnouncementVisibilityTest {

    private fun a(id: String, revision: Int = 1, endsAt: Long? = null) = UiAnnouncement(
        id = id, revision = revision, priority = 0, dismissible = true, endsAtMillis = endsAt,
        style = UiAnnouncementStyle.CARD, accent = UiAnnouncementAccent.NEUTRAL, icon = null, action = null,
        blocks = listOf(UiAnnouncementBlock.Title(id)),
    )

    @Test
    fun atMostTwoInTheServersOrder() {
        val shown = visibleAnnouncements(listOf(a("one"), a("two"), a("three")), emptySet(), nowMs = 0)
        assertEquals(listOf("one", "two"), shown.map { it.id })
    }

    @Test
    fun aDismissalHoldsUntilTheRevisionIsBumped() {
        val dismissed = parseDismissedAnnouncements(addDismissedAnnouncement(null, "one@1"))
        assertEquals(listOf("two", "three"), visibleAnnouncements(listOf(a("one"), a("two"), a("three")), dismissed, 0).map { it.id })
        assertEquals(listOf("one", "two"), visibleAnnouncements(listOf(a("one", revision = 2), a("two")), dismissed, 0).map { it.id })
    }

    @Test
    fun anExpiredOneIsNotDrawn() {
        val shown = visibleAnnouncements(listOf(a("old", endsAt = 1_000), a("open"), a("later", endsAt = 5_000)), emptySet(), nowMs = 1_000)
        assertEquals(listOf("open", "later"), shown.map { it.id })
    }

    @Test
    fun theDismissedListStaysShortAndUnique() {
        var pref: String? = null
        repeat(60) { pref = addDismissedAnnouncement(pref, "a$it@1") }
        pref = addDismissedAnnouncement(pref, "a59@1")
        val keys = parseDismissedAnnouncements(pref)
        assertEquals(50, keys.size)
        assertEquals(true, "a59@1" in keys)
        assertEquals(false, "a0@1" in keys)
        assertEquals(emptySet(), parseDismissedAnnouncements(" , "))
    }

    @Test
    fun clicksCountUnderAValidFeatureId() {
        assertEquals("announce_claude_launch", UiFeatures.announcement("claude-launch"))
        val long = UiFeatures.announcement("a".repeat(62))
        assertEquals(48, long.length)
        assertEquals(true, Regex("[a-z][a-z0-9_]{1,47}").matches(long))
    }
}
