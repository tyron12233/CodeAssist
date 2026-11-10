package dev.ide.core.backend

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.core.settings.BuiltInSettingsPages
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiSettingControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Build Runtime page is assembled by hand (its R8 controls depend on the device), and it used to list ONLY
 * those hand-built controls: low memory mode, app-log forwarding and the Kotlin compiler VM settings were read by
 * the app but never shown. The page now lists every control it declares, on a device; the hand-built ones once.
 */
class BuildRuntimeSettingsTest {

    private fun controls(device: Boolean, prefs: Map<String, String> = emptyMap()): List<UiSettingControl> =
        withTempDir("build-runtime") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            if (prefs.isNotEmpty()) manager.setPreferences(prefs)
            manager.create("java-library", mapOf("name" to "Lib", "packageName" to "com.acme.lib")).use { ide ->
                // A build-runner factory is what a device host (the `:build` process) supplies.
                val backend = if (device) IdeServicesBackend(ide, manager, buildRunnerFactory = { it.buildRunner }) else IdeServicesBackend(ide, manager)
                backend.settings.settingsPages().first { it.id == BuiltInSettingsPages.BUILD_RUNTIME }.controls
            }
        }

    @Test
    fun aDeviceListsEveryBuildRuntimeSettingOnce() {
        val c = controls(device = true)
        val keys = c.map { it.key }
        assertEquals(keys.distinct(), keys, "no control twice")
        for (k in listOf(BuiltInSettingsPages.PREBUILT_LIBRARIES, BuiltInSettingsPages.LOW_MEMORY_MODE,
                         BuiltInSettingsPages.INJECT_APP_LOG, BuiltInSettingsPages.KOTLINC_MODE)) {
            assertTrue(k in keys, "$k is listed: $keys")
        }
        val prebuilt = c.first { it.key == BuiltInSettingsPages.PREBUILT_LIBRARIES } as UiSettingControl.Choice
        assertEquals(listOf("auto", "wifi", "off"), prebuilt.options.map { it.value })
        assertEquals("auto", prebuilt.value)
    }

    @Test
    fun theR8HeapStaysHiddenInProcessEvenThoughItIsDeclared() {
        val keys = controls(device = true, mapOf("settings.buildRuntime.${BuiltInSettingsPages.R8_MODE}" to BuiltInSettingsPages.R8_MODE_INPROCESS)).map { it.key }
        assertFalse(BuiltInSettingsPages.R8_MAX_HEAP in keys, "$keys")
    }

    @Test
    fun aHostWithoutADeviceBuildDoesNotListThem() {
        val keys = controls(device = false).map { it.key }
        assertFalse(BuiltInSettingsPages.PREBUILT_LIBRARIES in keys, "$keys")
    }
}
