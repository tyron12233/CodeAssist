package dev.ide.android.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Console (JVM `main`) projects build and run on device: kotlinc/ecj, then the bytecode interpreter. */
@RunWith(AndroidJUnit4::class)
class ConsoleRunTest : E2eTest() {

    @Test
    fun kotlinConsoleAppRunsAndPrints() = runsAndPrints("Kotlin Console App", uniqueName("KtCon"))

    @Test
    fun javaConsoleAppRunsAndPrints() = runsAndPrints("Java Console App", uniqueName("JvCon"))

    private fun runsAndPrints(template: String, name: String) {
        createProject(template, name)
        run("Run app")
        // The template's main prints "Hello from <name>!"; the run screen ends with the exit line.
        awaitTextContains("Process finished with exit code", BUILD_TIMEOUT)
        awaitTextContains("Hello from $name!")
        awaitTextContains("Process finished with exit code 0")
    }
}
