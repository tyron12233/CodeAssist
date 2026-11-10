package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An XML layout previews: the layout is inflated as real views against the project's resources (the realview
 * runtime in dev.ide.preview, reached by name from the preview process), and the component tree lists what was
 * inflated. The Android App template's activity_main.xml is a LinearLayout holding a TextView.
 */
@RunWith(AndroidJUnit4::class)
class LayoutPreviewTest : E2eTest() {

    @Test
    fun templateLayoutPreviewsAndListsItsViews() {
        createProject("Android App", uniqueName("Lay"))
        openFile("activity_main")
        click(Regex("^(Preview|Design)$"), PROJECT_TIMEOUT)

        // The real-view preview may first ask to prepare (dex) the project's libraries once. The tree panel opens
        // from the pane's top bar (a toggle, so it is tapped once) and lists the inflated views once a render exists.
        var treeOpened = false
        val tree = poll(BUILD_TIMEOUT, 2_000) {
            val screen = screenText()
            if ("Prepare libraries" in screen && "Preparing libraries" !in screen) clickIfPresent("Prepare libraries")
            if (Regex("(?i)preview failed|failed to render").containsMatchIn(screen)) failWithScreen("the layout preview failed")
            if (!treeOpened) {
                device.findObject(androidx.test.uiautomator.By.desc("Component tree"))?.let { it.click(); treeOpened = true }
                return@poll null
            }
            screen.takeIf { "LinearLayout" in it && "TextView" in it }
        }
        if (tree == null) failWithScreen("the layout preview never listed the template's LinearLayout and TextView")
    }
}
