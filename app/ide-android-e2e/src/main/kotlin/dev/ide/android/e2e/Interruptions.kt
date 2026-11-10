package dev.ide.android.e2e

import androidx.test.uiautomator.By

/**
 * Sheets and overlays that can sit on top of the flow under test: first-run sheets the seed missed, the
 * learn-tour offer, the notification prompt, and test interstitial ads (the `profile` build serves Google's
 * test units). Each entry is a label that proves the interruption is up, and the control that dismisses it.
 */
object Interruptions {
    private val sheets = listOf(
        "A new build system" to "Got it",
        "A real IDE. On your phone." to "Skip for now",
        "Help improve CodeAssist" to "No thanks",
        "Learn CodeAssist in a few minutes" to "Not now",
        "Show build notifications?" to "Not now",
        "Builds will run in the app" to "Got it",
    )

    /** Dismisses whatever is up, repeatedly, until a short quiet period passes with nothing to dismiss. */
    fun dismissAll(test: E2eTest, quietMs: Long = 2_500) {
        var quietSince = System.currentTimeMillis()
        val deadline = quietSince + 30_000
        while (System.currentTimeMillis() < deadline && System.currentTimeMillis() - quietSince < quietMs) {
            if (dismissOne(test)) quietSince = System.currentTimeMillis() else Thread.sleep(250)
        }
    }

    fun dismissOne(test: E2eTest): Boolean {
        for ((proof, dismiss) in sheets) {
            if (test.findNow(proof) != null && test.clickIfPresent(dismiss)) return true
        }
        // Google test interstitial: close control is an ImageButton described "Interstitial close button".
        test.device.findObject(By.desc("Interstitial close button"))?.let { it.click(); return true }
        return false
    }
}
