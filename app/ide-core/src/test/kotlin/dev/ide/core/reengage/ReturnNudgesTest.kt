package dev.ide.core.reengage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReturnNudgesTest {

    private val day = ReturnNudges.DAY_MS

    private fun state(
        installedAt: Long = 0,
        lastUsedAt: Long = 0,
        everBuilt: Boolean = false,
        failed: FailedBuild? = null,
        sentCount: Int = 0,
        lastSentAt: Long = 0,
        sentKeys: Set<String> = emptySet(),
        enabled: Boolean = true,
        opened: ProjectRef? = null,
        firstBuiltAt: Long = 0,
        built: ProjectRef? = null,
    ) = ReturnState(
        installedAt, lastUsedAt, everBuilt, failed, opened, firstBuiltAt, built, sentCount, lastSentAt, sentKeys, enabled,
    )

    @Test
    fun anInstallThatNeverBuiltIsRemindedTwoDaysAfterItsLastUse() {
        val s = state(installedAt = 0, lastUsedAt = day)
        assertNull(ReturnNudges.due(s, 3 * day - 1))
        assertEquals(ReturnNudgeKind.NEVER_BUILT, ReturnNudges.due(s, 3 * day)?.kind)
        assertEquals(3 * day, ReturnNudges.nextCheckAt(s))
    }

    @Test
    fun usingTheAppMovesTheReminderBack() {
        val away = state(lastUsedAt = 0)
        assertEquals(2 * day, ReturnNudges.nextCheckAt(away))
        assertEquals(2 * day + 5 * day, ReturnNudges.nextCheckAt(away.copy(lastUsedAtMs = 5 * day)))
    }

    @Test
    fun aFailedBuildIsMentionedADayAfterTheUserLeft_andNamesTheProject() {
        val failed = FailedBuild("/p/app", "MyApp", atMs = 10)
        val s = state(everBuilt = true, lastUsedAt = 100, failed = failed)
        assertNull(ReturnNudges.due(s, 100 + day - 1))
        val nudge = ReturnNudges.due(s, 100 + day)
        assertEquals(ReturnNudgeKind.FAILED_BUILD, nudge?.kind)
        assertEquals("/p/app", nudge?.projectRoot)
        assertEquals("MyApp", nudge?.projectName)
    }

    @Test
    fun aFailedBuildComesBeforeNeverHavingBuilt() {
        val s = state(lastUsedAt = 0, failed = FailedBuild("/p", "P", atMs = 0))
        assertEquals(ReturnNudgeKind.FAILED_BUILD, ReturnNudges.due(s, 10 * day)?.kind)
    }

    @Test
    fun aReminderIsShownOnce_butALaterFailureIsANewOne() {
        val first = FailedBuild("/p", "P", atMs = 0)
        val shown = ReturnNudges.due(state(everBuilt = true, failed = first), 10 * day)!!
        val after = state(everBuilt = true, failed = first, sentCount = 1, lastSentAt = 10 * day, sentKeys = setOf(shown.key))
        assertNull(ReturnNudges.due(after, 100 * day))
        assertNull(ReturnNudges.nextCheckAt(after))

        val again = after.copy(failedBuild = FailedBuild("/p", "P", atMs = 20 * day), lastUsedAtMs = 20 * day)
        assertEquals(ReturnNudgeKind.FAILED_BUILD, ReturnNudges.due(again, 21 * day)?.kind)
    }

    @Test
    fun twoRemindersAreAtLeastAWeekApart() {
        val s = state(lastUsedAt = 0, sentCount = 1, lastSentAt = 3 * day, sentKeys = setOf("failed_build:0"))
        assertNull(ReturnNudges.due(s, 3 * day + ReturnNudges.MIN_GAP_MS - 1))
        assertEquals(ReturnNudgeKind.NEVER_BUILT, ReturnNudges.due(s, 3 * day + ReturnNudges.MIN_GAP_MS)?.kind)
        assertEquals(3 * day + ReturnNudges.MIN_GAP_MS, ReturnNudges.nextCheckAt(s))
    }

    @Test
    fun anInstallHearsNothingAfterItsShareOrWhenSwitchedOff() {
        val used = state(sentCount = ReturnNudges.MAX_SENT)
        assertNull(ReturnNudges.due(used, 100 * day))
        assertNull(ReturnNudges.nextCheckAt(used))
        val off = state(enabled = false)
        assertNull(ReturnNudges.due(off, 100 * day))
        assertNull(ReturnNudges.nextCheckAt(off))
    }

    @Test
    fun aProjectThatWasNeverBuiltIsWhatTheReminderIsAbout() {
        val s = state(lastUsedAt = 0, opened = ProjectRef("/p/app", "MyApp"))
        val nudge = ReturnNudges.due(s, 2 * day)
        assertEquals(ReturnNudgeKind.UNBUILT_PROJECT, nudge?.kind)
        assertEquals("/p/app", nudge?.projectRoot)
        // Not also the reminder for an install with no project at all.
        val after = s.copy(sentCount = 1, lastSentAtMs = 2 * day, sentKeys = setOf(nudge!!.key))
        assertNull(ReturnNudges.due(after, 100 * day))
    }

    @Test
    fun aFirstBuildIsFollowedUpThreeDaysAfterTheUserLeft() {
        val s = state(everBuilt = true, lastUsedAt = day, firstBuiltAt = day, built = ProjectRef("/p/app", "MyApp"))
        assertNull(ReturnNudges.due(s, 4 * day - 1))
        val nudge = ReturnNudges.due(s, 4 * day)
        assertEquals(ReturnNudgeKind.FIRST_BUILD, nudge?.kind)
        assertEquals("MyApp", nudge?.projectName)
    }

    @Test
    fun someoneWhoKeptUsingTheAppForWeeksIsNotFollowedUpOnTheirFirstBuild() {
        val s = state(
            everBuilt = true, firstBuiltAt = 1, lastUsedAt = ReturnNudges.FIRST_BUILD_WINDOW_MS + 2,
            built = ProjectRef("/p", "P"),
        )
        assertNull(ReturnNudges.due(s, 100 * day))
        assertNull(ReturnNudges.nextCheckAt(s))
    }

    @Test
    fun aFailedBuildComesBeforeAFirstBuildFollowUp() {
        val s = state(
            everBuilt = true, lastUsedAt = day, firstBuiltAt = day, built = ProjectRef("/p", "P"),
            failed = FailedBuild("/q", "Q", atMs = day),
        )
        assertEquals(ReturnNudgeKind.FAILED_BUILD, ReturnNudges.due(s, 10 * day)?.kind)
    }

    @Test
    fun someoneWhoBuiltAndLeftNothingBrokenIsNotReminded() {
        val s = state(everBuilt = true)
        assertNull(ReturnNudges.due(s, 100 * day))
        assertNull(ReturnNudges.nextCheckAt(s))
    }
}
