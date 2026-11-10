package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Test
import org.junit.runner.RunWith

/** The Kotlin editor backend on the minified build: completion and diagnostics while typing. */
@RunWith(AndroidJUnit4::class)
class EditorTest : E2eTest() {

    @Test
    fun completionAndDiagnosticsWhileTyping() {
        val name = uniqueName("Edit")
        createProject("Kotlin Console App", name)
        openFile("Main")
        // The code area is a canvas with its own input connection (no accessibility text): focus it, go to
        // the end of the line, and type through injected key events.
        device.click(device.displayWidth / 2, device.displayHeight * 2 / 5)
        Thread.sleep(500)
        repeat(3) { shell("input keyevent KEYCODE_PAGE_DOWN") }
        shell("input keyevent KEYCODE_MOVE_END")
        shell("input keyevent KEYCODE_ENTER")
        // Plain lowercase only: `input text` mangles shifted characters and brackets.
        shell("input text printl")
        // Completion rows carry the signature as text: "println(message: Any?)".
        await(By.textStartsWith("println("), 30_000)
        shell("input keyevent KEYCODE_ESCAPE")
        // A dangling `printl` doesn't parse: the editor reports it (inline "Syntax error" or the tab marker).
        await(Regex("^(Syntax error|\\d+ (build )?errors?)$"), 60_000)
    }
}
