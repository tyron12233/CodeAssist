package dev.ide.desktop

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which window a Quit closes instead of quitting the IDE. Checked over stand-in windows, since a test JVM is
 * headless and cannot create real ones; the owner chain is all the decision reads.
 */
class QuitGuardTest {

    private class W(val name: String, val owner: W? = null)

    private val ide = W("ide")

    private fun target(active: W?, ideWindow: W? = ide) = programWindowToClose(active, ideWindow) { it.owner }

    @Test fun quittingWithTheIdeInFrontQuits() {
        assertNull(target(ide))
    }

    @Test fun aDialogTheIdeOpenedCountsAsTheIde() {
        assertNull(target(W("settings dialog", owner = W("panel", owner = ide))))
    }

    @Test fun aProgramsWindowIsClosedInstead() {
        val program = W("KMP Desktop Smoke")
        assertSame(program, target(program))
    }

    @Test fun aDialogOwnedByTheProgramClosesThatDialog() {
        val dialog = W("about", owner = W("program"))
        assertSame(dialog, target(dialog))
    }

    @Test fun withNothingInFrontOrNoIdeWindowYetItQuits() {
        assertNull(target(null))
        assertNull(target(W("program"), ideWindow = null))
    }
}
