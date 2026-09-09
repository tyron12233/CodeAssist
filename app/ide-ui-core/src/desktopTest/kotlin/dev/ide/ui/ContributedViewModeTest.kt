package dev.ide.ui

import dev.ide.ui.ext.EditorViewModeContribution
import dev.ide.ui.ext.ViewModeRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contributed editor surfaces: how they are resolved for a file, and how one claims the surface a file opens
 * into.
 *
 * [EditorViewMode] became an open value over its persisted id for exactly this: an enum left no way to name a
 * mode a plugin contributed, and the four built-in ids are unchanged, so nothing about the on-disk session
 * format moved.
 */
class ContributedViewModeTest {

    private val disposals = ArrayList<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        disposals.forEach { it() }
        disposals.clear()
    }

    private fun register(
        id: String,
        order: Int = 1000,
        appliesTo: (String) -> Boolean = { true },
        isDefault: (String) -> Boolean = { false },
    ) {
        val reg = ViewModeRegistry.register(
            EditorViewModeContribution(
                id = id,
                label = id,
                order = order,
                appliesTo = appliesTo,
                isDefault = isDefault,
            ) {},
        )
        disposals += { reg.dispose() }
    }

    // ---- the open vocabulary ----

    @Test
    fun theBuiltInModesKeepTheIdsTheyWerePersistedUnder() {
        assertEquals(listOf("text", "blocks", "preview", "split"), EditorViewMode.BUILT_IN.map { it.id })
        assertEquals("text", EditorViewMode.Text.persistId())
        assertEquals(EditorViewMode.Split, editorViewModeOf("split"))
    }

    @Test
    fun aContributedModeIdRoundTripsThroughTheSessionFormat() {
        val mode = EditorViewMode("com.example.scene")
        assertEquals(mode, editorViewModeOf(mode.persistId()))
    }

    @Test
    fun aBlankPersistedModeIsNoMode() {
        assertNull(editorViewModeOf(null))
        assertNull(editorViewModeOf(""))
        assertNull(editorViewModeOf("   "))
    }

    @Test
    fun anIdThisBuildDoesNotKnowIsKeptRatherThanDiscarded() {
        // It may belong to a plugin that is not loaded yet; the dispatch falls through to the code editor,
        // which is where an unknown id landed before this was an open vocabulary.
        assertEquals(EditorViewMode("from-a-plugin"), editorViewModeOf("from-a-plugin"))
    }

    // ---- resolution ----

    @Test
    fun modesResolveForTheFilesTheyClaimInOrder() {
        register("late", order = 20)
        register("early", order = 10)
        register("kotlin-only", appliesTo = { it.endsWith(".kt") })

        assertEquals(
            listOf("early", "late", "kotlin-only"),
            ViewModeRegistry.forFile("/p/App.kt").map { it.id },
        )
        assertEquals(listOf("early", "late"), ViewModeRegistry.forFile("/p/App.java").map { it.id })
    }

    @Test
    fun aModeWhosePredicateThrowsIsSkippedRatherThanBreakingTheToggle() {
        register("thrower", appliesTo = { error("boom") })
        register("fine")

        assertEquals(listOf("fine"), ViewModeRegistry.forFile("/p/App.kt").map { it.id })
    }

    // ---- claiming the surface a file opens into ----

    @Test
    fun aModeCanClaimTheSurfaceAFileOpensInto() {
        register("scene", appliesTo = { it.endsWith(".scene") }, isDefault = { it.endsWith(".scene") })

        assertEquals("scene", ViewModeRegistry.defaultFor("/p/Level.scene")?.id)
        assertNull(ViewModeRegistry.defaultFor("/p/App.kt"), "a file it does not claim opens as code")
    }

    @Test
    fun theFirstClaimantKeepsTheFileKind() {
        register("first", order = 10, isDefault = { true })
        register("second", order = 20, isDefault = { true })

        assertEquals("first", ViewModeRegistry.defaultFor("/p/Level.scene")?.id)
    }

    @Test
    fun claimingADefaultWithoutClaimingTheFileAtAllIsNotADefault() {
        // isDefault says "open here"; appliesTo says "offer at all". A mode that opens a file it does not
        // otherwise claim would show a pane its own toggle segment is missing from.
        register("inconsistent", appliesTo = { false }, isDefault = { true })

        assertNull(ViewModeRegistry.defaultFor("/p/Level.scene"))
    }

    @Test
    fun aThrowingDefaultPredicateCannotStopAFileFromOpening() {
        register("thrower", isDefault = { error("boom") })

        assertNull(ViewModeRegistry.defaultFor("/p/App.kt"))
    }

    // ---- the tab ----

    @Test
    fun aTabOpensIntoThePluginsModeWhenItClaimsTheKind() {
        register("scene", appliesTo = { it.endsWith(".scene") }, isDefault = { it.endsWith(".scene") })

        val claimed = OpenFile(path = "/p/Level.scene", name = "Level.scene", initial = "{}")
        assertEquals(EditorViewMode("scene"), claimed.viewMode)

        val ordinary = OpenFile(path = "/p/App.kt", name = "App.kt", initial = "fun main() {}")
        assertEquals(EditorViewMode.Text, ordinary.viewMode, "an unclaimed file still opens as code")
    }

    @Test
    fun aPluginsClaimBeatsTheBuiltInImageRule() {
        // The IDE opens a bitmap straight into its own preview. A plugin's image editor has to be able to
        // take that over, or "own this file kind" would exclude every kind the IDE already has a view for.
        register("paint", appliesTo = { it.endsWith(".png") }, isDefault = { it.endsWith(".png") })

        val tab = OpenFile(path = "/p/app/src/main/res/drawable/icon.png", name = "icon.png", initial = "")
        assertEquals(EditorViewMode("paint"), tab.viewMode)
    }

    @Test
    fun aBitmapWithNoClaimStillOpensInPreview() {
        val tab = OpenFile(path = "/p/app/src/main/res/drawable/icon.png", name = "icon.png", initial = "")
        assertEquals(EditorViewMode.Preview, tab.viewMode)
        assertTrue(ViewModeRegistry.all().isEmpty(), "nothing registered, so the built-in rule decided")
    }
}
