package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Swing program runs on the device: its bytecode is interpreted against the IDE's AWT/Swing support, which the
 * interpreter reaches by name (dev.ide.awt / dev.ide.swing), and its window streams into the Run screen.
 */
@RunWith(AndroidJUnit4::class)
class SwingRunTest : E2eTest() {

    @Test
    fun swingDesktopAppOpensItsWindow() {
        val name = uniqueName("Swing")
        createProject("Swing Desktop App", name)
        run("Run app")
        // The window only exists once the program has painted a frame; "Running" says it is still alive (a crash
        // in the interpreter or the Swing support ends the run as Failed instead).
        await(By.desc("Program window"), BUILD_TIMEOUT)
        await("Running")
        Thread.sleep(5_000)
        if (findNow(Regex("^(Failed|Build failed)$")) != null) failWithScreen("the Swing program stopped after opening its window")
        await(By.desc("Program window"))
        click("Stop")
    }
}
