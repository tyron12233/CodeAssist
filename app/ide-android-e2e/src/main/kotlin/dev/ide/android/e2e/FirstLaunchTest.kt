package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** A fresh install walks its first-run sheets (migration, onboarding, analytics, tour) to the project list. */
@RunWith(AndroidJUnit4::class)
class FirstLaunchTest : E2eTest() {
    override val clearAppData = true
    override fun seed() = emptyMap<String, String>()

    @Test
    fun freshInstallReachesTheProjectList() {
        // Each sheet is real UI from the minified build; dismiss them through their own buttons.
        val seen = mutableListOf<String>()
        val reached = poll(LAUNCH_TIMEOUT) {
            if (Interruptions.dismissOne(this)) seen += "dismissed"
            findNow("New project")?.takeIf { noSheetUp() }
        }
        if (reached == null) failWithScreen("never reached the project list (dismissed ${seen.size} sheet(s))")
        if (seen.isEmpty()) failWithScreen("a fresh install showed no first-run sheet at all")
    }

    private fun noSheetUp() = listOf("A new build system", "A real IDE. On your phone.", "Help improve CodeAssist")
        .none { findNow(it) != null }
}
