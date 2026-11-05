package dev.ide.core.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginCrashGuardTest {

    private class MapPrefs : PluginCrashGuard.Prefs {
        val values = HashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun set(values: Map<String, String>) { this.values.putAll(values) }
    }

    private val prefs = MapPrefs()
    private val guard = PluginCrashGuard(prefs)
    private val dart = PluginCrashGuard.Blamed("com.example.dart", "1.2.0", "Dart")

    @Test
    fun aBlamedFatalCrashTurnsThePluginOffAndLeavesANotice() {
        guard.onFatalCrash(dart, uptimeMs = 5_000, disabled = setOf("blocks"))
        assertEquals("blocks,com.example.dart", prefs.values[PluginCrashGuard.DISABLED_KEY])
        assertEquals(mapOf("com.example.dart" to "1.2.0"), guard.crashSuspended())
        val notice = guard.takeNotice()
        assertEquals("com.example.dart", notice?.pluginId)
        assertEquals("Dart was turned off", notice?.title)
        assertNull(guard.takeNotice(), "a notice is shown once")
    }

    @Test
    fun aBlamedCrashDoesNotCountTowardSafeMode() {
        repeat(3) { guard.onFatalCrash(dart, uptimeMs = 1_000, disabled = emptySet()) }
        assertFalse(guard.safeModeRequested())
    }

    @Test
    fun repeatedUnexplainedEarlyCrashesAskForSafeModeOnce() {
        guard.onFatalCrash(null, uptimeMs = 2_000, disabled = emptySet())
        assertFalse(guard.safeModeRequested())
        guard.onFatalCrash(null, uptimeMs = 3_000, disabled = emptySet())
        assertTrue(guard.safeModeRequested())

        guard.afterLoad(lifted = emptySet(), safeModeApplied = true)
        assertFalse(guard.safeModeRequested(), "the launch after safe mode tries the plugins again")
        assertEquals("Started without plugins", guard.takeNotice()?.title)
    }

    @Test
    fun aLateCrashOrAStableLaunchDoesNotBuildTowardSafeMode() {
        guard.onFatalCrash(null, uptimeMs = 2_000, disabled = emptySet())
        guard.onFatalCrash(null, uptimeMs = PluginCrashGuard.QUICK_CRASH_MS + 1, disabled = emptySet())
        assertFalse(guard.safeModeRequested())
        guard.markStable()
        guard.onFatalCrash(null, uptimeMs = 2_000, disabled = emptySet())
        assertFalse(guard.safeModeRequested())
    }

    @Test
    fun safeModeWithNoInstalledPluginsSaysNothing() {
        repeat(2) { guard.onFatalCrash(null, uptimeMs = 1_000, disabled = emptySet()) }
        guard.afterLoad(lifted = emptySet(), safeModeApplied = false)
        assertNull(guard.takeNotice())
        assertFalse(guard.safeModeRequested())
    }

    @Test
    fun forgettingClearsOnlyTheNamedSuspensions() {
        guard.onFatalCrash(dart, uptimeMs = 1_000, disabled = emptySet())
        guard.onFatalCrash(PluginCrashGuard.Blamed("com.example.lsp", "2.0", "LSP"), uptimeMs = 1_000, disabled = emptySet())
        guard.forget(listOf("com.example.dart"))
        assertEquals(mapOf("com.example.lsp" to "2.0"), guard.crashSuspended())
    }
}
