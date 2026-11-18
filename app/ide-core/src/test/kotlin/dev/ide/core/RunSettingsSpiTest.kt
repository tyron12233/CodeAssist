package dev.ide.core

import dev.ide.build.RUN_SETTINGS
import dev.ide.build.RunSettings
import dev.ide.plugin.editor.GutterActions
import dev.ide.ui.backend.RunGutter
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a plugin with a Run of its own reads and writes without reaching past the SPI: the entry point chosen
 * on a module's Run card (`platform.runSettings`), and the gutter action id that starts a Run task.
 */
class RunSettingsSpiTest {

    private val root = createTempDirectory("run-settings-spi")
    private val services: IdeServices = IdeServices.bootstrapJavaDemo(root)

    @AfterTest
    fun tearDown() {
        services.close()
        root.toFile().deleteRecursively()
    }

    private val settings: RunSettings
        get() = services.store.workspace.service(RUN_SETTINGS)

    @Test
    fun `an entry point left to be detected reads as none`() {
        val module = services.store.workspace.projects.first().modules.first()
        assertNull(settings.entryPoint(module))
    }

    @Test
    fun `the entry point is the one the Run card stored`() {
        val module = services.store.workspace.projects.first().modules.first()
        services.setProjectPref("module.mainClass.${module.id.value}", "  demo.Main ")

        assertEquals("demo.Main", settings.entryPoint(module))

        services.setProjectPref("module.mainClass.${module.id.value}", "")
        assertNull(settings.entryPoint(module))
    }

    @Test
    fun `the published gutter id is the one the editor starts a run for`() {
        assertEquals(RunGutter.ACTION_PREFIX, GutterActions.RUN_TASK_PREFIX)
        assertEquals(RunGutter.ACTION_PREFIX + "csRun:app", GutterActions.runTask("csRun:app"))
    }

    @Test
    fun `a task the editor cannot name is left for its provider to label`() {
        check(RunGutter.isUnnamed(RunGutter.option("csRun:app")))
        check(!RunGutter.isUnnamed(RunGutter.option(RunGutter.mainTaskId("app", "demo.Main"))))
    }
}
