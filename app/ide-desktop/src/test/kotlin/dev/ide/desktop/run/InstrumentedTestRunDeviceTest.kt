package dev.ide.desktop.run

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.model.template.TemplateArgs
import dev.ide.ui.backend.RunPhase
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.UiTestStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An Android app's instrumented tests, end to end on a real device or emulator: the Android App template plus an
 * `androidTest` class and androidx.test, built into the app and test APKs, installed over adb, run with
 * `am instrument`, and read back into the Run screen's results. Opt-in (`RUN_DEVICE_TESTS=1`) because it needs a
 * connected device and the network.
 */
@Timeout(1200, unit = TimeUnit.SECONDS)
class InstrumentedTestRunDeviceTest {

    @Test
    fun runsAnAppsInstrumentedTestsOnADevice() {
        assumeTrue(System.getenv("RUN_DEVICE_TESTS") == "1", "opt-in: set RUN_DEVICE_TESTS=1 with a device connected")
        val installer = DesktopApkInstaller()
        installer.targets()
        Thread.sleep(3_000) // the first device poll
        assumeTrue(installer.targets().isNotEmpty(), "no device connected")

        val tmp = Files.createTempDirectory("instrumented-e2e")
        val manager = ProjectManager.desktop(tmp.resolve("projects"), apkInstaller = installer)
        try {
            manager.create(
                "android-app",
                mapOf(TemplateArgs.NAME to "Instr", TemplateArgs.PACKAGE to "com.example.instr", "language" to "kotlin"),
            ).use { ide ->
                val backend = IdeServicesBackend(ide)
                runBlocking {
                    withTimeout(600_000) {
                        for (coordinate in listOf("androidx.test.ext:junit:1.2.1", "androidx.test:runner:1.6.2")) {
                            val added = backend.deps.addDependency("app", coordinate, "testImplementation")
                            assertTrue(added.success, "$coordinate: ${added.message}")
                        }
                    }
                }
                val app = ide.modules().single()
                val dir = tmp.resolve("projects").let { root -> Files.walk(root).use { s -> s.filter { it.endsWith("app/src/main") }.findFirst().get() } }.parent
                val test = dir.resolve("androidTest/kotlin/com/example/instr/ExampleInstrumentedTest.kt")
                Files.createDirectories(test.parent)
                Files.writeString(test, TEST)

                assertTrue(backend.build.runTasks().any { it.id == "androidTest:${app.name}" }, "offered: ${backend.build.runTasks().map { it.id }}")
                backend.build.runTask("androidTest:${app.name}")
                val deadline = System.currentTimeMillis() + 900_000
                while (backend.build.runConsole.value?.phase != RunPhase.Finished && System.currentTimeMillis() < deadline) Thread.sleep(200)
                val rc = assertNotNull(backend.build.runConsole.value)
                val log = backend.build.buildState.value.log.joinToString("\n") { it.message }
                val raw = rc.transcript.joinToString("") { it.text }.replace(TestOutput.MARK, "<M>")
                val tests = assertNotNull(TestOutput.split(rc.transcript).tests, "no results.\ntranscript:\n$raw\nlog:\n$log")
                println(raw)
                assertEquals(TestOutput.INSTRUMENTED, tests.kind)
                val byName = tests.cases.associateBy { it.name }
                assertEquals(UiTestStatus.Passed, byName["usesTheAppContext"]?.status, "$raw\n$log")
                assertEquals(UiTestStatus.Failed, byName["failsOnPurpose"]?.status, raw)
                assertTrue("expected:<1> but was:<2>" in byName.getValue("failsOnPurpose").message, byName.getValue("failsOnPurpose").message)
                assertEquals(1, rc.exitCode)
            }
        } finally {
            manager.dispose()
        }
    }

    private companion object {
        const val TEST = """
package com.example.instr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun usesTheAppContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.instr", context.packageName)
    }

    @Test
    fun failsOnPurpose() {
        assertEquals(1, 2)
    }
}
"""
    }
}
