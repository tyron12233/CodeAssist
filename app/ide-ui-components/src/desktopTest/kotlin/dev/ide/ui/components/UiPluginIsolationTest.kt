package dev.ide.ui.components

import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.ext.ToolWindowContribution
import dev.ide.ui.ext.ToolWindowRegistry
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin
import dev.ide.ui.ext.UiPluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A UI plugin whose `contributeUi` throws must not take app startup down with it: an installed plugin built
 * against an older host fails with a `NoSuchMethodError` there, and the host used to let it escape, crashing
 * every launch. It is withdrawn whole and recorded; the next plugin still loads.
 */
class UiPluginIsolationTest {

    private fun window(id: String) = ToolWindowContribution(id, id, "icon", ToolWindowAnchor.RIGHT) {}

    private fun ids() = ToolWindowRegistry.forAnchor(ToolWindowAnchor.RIGHT).map { it.id }

    @Test
    fun aPluginThatThrowsIsWithdrawnAndRecorded() {
        val broken = object : UiPlugin {
            override val id = "test.broken"
            override fun contributeUi(scope: UiContributionScope) {
                scope.toolWindow(window("test.broken.window"))
                throw NoSuchMethodError("dev.ide.Gone.method()V")
            }
        }
        val healthy = object : UiPlugin {
            override val id = "test.healthy"
            override fun contributeUi(scope: UiContributionScope) {
                scope.toolWindow(window("test.healthy.window"))
            }
        }

        assertFalse(UiPluginHost.contribute(broken))
        assertTrue(UiPluginHost.contribute(healthy))

        assertFalse("test.broken.window" in ids(), "the half-registered window is withdrawn")
        assertTrue("test.healthy.window" in ids(), "the next plugin still loads")
        assertIs<NoSuchMethodError>(UiPluginHost.failures["test.broken"])
        assertNull(UiPluginHost.failures["test.healthy"])
        assertEquals(1, ids().count { it == "test.healthy.window" })
    }
}
