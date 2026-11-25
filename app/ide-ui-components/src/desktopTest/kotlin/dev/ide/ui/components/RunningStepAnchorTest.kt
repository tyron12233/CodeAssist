package dev.ide.ui.components

import dev.ide.ui.backend.BuildStepUi
import dev.ide.ui.backend.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Where the build console's Steps list opens and scrolls to while a build runs. */
class RunningStepAnchorTest {

    private fun steps(vararg statuses: StepStatus) = statuses.mapIndexed { i, s -> BuildStepUi(":task$i", s) }

    /** Nothing running (idle, or a finished build): no anchor, so the list keeps its current position. */
    @Test
    fun noRunningStepHasNoAnchor() {
        assertNull(runningStepAnchor(emptyList()))
        assertNull(runningStepAnchor(steps(StepStatus.Done, StepStatus.UpToDate, StepStatus.Failed)))
        assertNull(runningStepAnchor(steps(StepStatus.Pending, StepStatus.Pending)))
    }

    /** Deep in the graph, the anchor sits two steps above the running one so it shows with some context. */
    @Test
    fun anchorKeepsContextAboveTheRunningStep() {
        val s = steps(*Array(10) { StepStatus.Done }, StepStatus.Running, StepStatus.Pending, StepStatus.Pending)
        assertEquals(8, runningStepAnchor(s))
        assertEquals(10, runningStepAnchor(s, context = 0))
    }

    /** Near the top there is no room for the context, so the anchor clamps to the first row. */
    @Test
    fun anchorClampsAtTheTop() {
        assertEquals(0, runningStepAnchor(steps(StepStatus.Running, StepStatus.Pending)))
        assertEquals(0, runningStepAnchor(steps(StepStatus.Done, StepStatus.Running)))
    }

    /** With parallel tasks running, the earliest one anchors, so the rest show below it. */
    @Test
    fun parallelRunningStepsAnchorOnTheEarliest() {
        val s = steps(StepStatus.Done, StepStatus.Done, StepStatus.Done, StepStatus.Running, StepStatus.Done, StepStatus.Running)
        assertEquals(1, runningStepAnchor(s))
    }
}
