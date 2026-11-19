package dev.ide.core

import dev.ide.core.perf.FeatureTrail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTrailTest {

    @Test
    fun theTrailKeepsTheLastFewFeaturesInOrder() {
        val trail = FeatureTrail(size = 3)
        assertNull(trail.render(), "nothing used, nothing to send")
        listOf("screen_projects", "screen_editor", "tool_build", "tool_build", "view_blocks").forEach(trail::note)
        assertEquals("screen_editor>tool_build>view_blocks", trail.render(), "oldest dropped, a repeat noted once")
    }

    @Test
    fun anIdOutsideTheFixedSetIsNotKept() {
        val trail = FeatureTrail()
        trail.note("My Secret Project")
        trail.note("screen_editor")
        assertEquals("screen_editor", trail.render())
        trail.clear()
        assertNull(trail.render(), "withdrawn consent leaves nothing behind")
    }
}
