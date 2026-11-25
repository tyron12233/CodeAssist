package dev.ide.core

import dev.ide.platform.ExtensionPoint
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val LIVE_EP = ExtensionPoint<String>("test.live.ep")

/** A plugin installed while the app runs. */
class LiveFirstBuild : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(LIVE_EP, "live-1")
    }
}

/** Its next build. */
class LiveSecondBuild : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(LIVE_EP, "live-2")
    }
}

/** Its UI facet. */
class LiveUiFacet : dev.ide.plugin.ui.UiPlugin {
    override val id = "com.example.live"
    override fun contribute(ui: dev.ide.plugin.ui.UiRegistration) = Unit
}

/** A plugin app as the package manager would report it: its origin label is the package, the same every build. */
private class LiveBuild(
    id: String,
    version: String,
    entryPoint: String,
    uiEntryPoints: List<String> = emptyList(),
    dependsOn: List<String> = emptyList(),
    pkg: String = id,
) : DiscoveredPlugin {
    override val manifest = PluginManifest(
        id = id, name = "Live $id", version = version, apiVersion = PLUGIN_API_VERSION, dependsOn = dependsOn,
        entryPoints = listOf(entryPoint), uiEntryPoints = uiEntryPoints, trusted = false,
    )
    override val origin = PluginOrigin("apk", pkg)
    override fun classLoader(): ClassLoader = javaClass.classLoader
}

/**
 * A plugin app installed, allowed, turned off, updated and uninstalled while the app runs is taken up at once,
 * without a restart. Before, each of these only recorded a change for the next launch.
 */
class PluginLiveInstallTest {
    private val id = "com.example.live"

    private fun first() = LiveBuild(id, "1", LiveFirstBuild::class.java.name, uiEntryPoints = listOf(LiveUiFacet::class.java.name))

    @Test
    fun `a plugin installed while the app runs is listed at once and does not run before consent`() {
        ApplicationEnvironment().use { env ->
            val r = env.pluginAppeared(first())

            assertIs<ApplicationEnvironment.PluginArrival.Listed>(r)
            assertTrue(env.pluginCatalog.needsConsent(id), "it waits for the user's answer")
            assertTrue(env.installedPlugins.any { it.manifest.id == id })
            assertFalse(env.isPluginRunning(id))
            assertTrue(env.platform.extensions.extensions(LIVE_EP).isEmpty(), "nothing of it ran")
        }
    }

    @Test
    fun `allowing it loads it in place, with its UI`() {
        ApplicationEnvironment().use { env ->
            env.pluginAppeared(first())
            env.setPluginChoices(disabled = emptySet(), consented = setOf(id))

            val r = env.loadPluginNow(id)

            assertIs<ApplicationEnvironment.PluginReload.Reloaded>(r)
            assertNotNull(r.ui)
            assertTrue(env.isPluginRunning(id))
            assertEquals(listOf("live-1"), env.platform.extensions.extensions(LIVE_EP))
            assertTrue(env.enabledUiPlugins.any { it.id == id })
            assertFalse(env.pluginCatalog.needsConsent(id))
        }
    }

    @Test
    fun `turning it off unloads it in place`() {
        ApplicationEnvironment().use { env ->
            env.pluginAppeared(first())
            env.setPluginChoices(emptySet(), setOf(id))
            env.loadPluginNow(id)

            assertEquals(null, env.unloadPluginNow(id))

            assertFalse(env.isPluginRunning(id))
            assertTrue(env.platform.extensions.extensions(LIVE_EP).isEmpty())
            assertTrue(env.enabledUiPlugins.none { it.id == id })
            assertTrue(env.installedPlugins.any { it.manifest.id == id }, "still installed, just off")
        }
    }

    @Test
    fun `a new build of a running plugin replaces it in place`() {
        ApplicationEnvironment().use { env ->
            env.pluginAppeared(first())
            env.setPluginChoices(emptySet(), setOf(id))
            env.loadPluginNow(id)

            val r = env.pluginAppeared(LiveBuild(id, "2", LiveSecondBuild::class.java.name))

            assertIs<ApplicationEnvironment.PluginArrival.Reloaded>(r)
            assertEquals(listOf("live-2"), env.platform.extensions.extensions(LIVE_EP))
            assertEquals("2", env.installedPlugins.single { it.manifest.id == id }.manifest.version)
        }
    }

    @Test
    fun `uninstalling it unloads it and drops it from the lists`() {
        ApplicationEnvironment().use { env ->
            env.pluginAppeared(first())
            env.setPluginChoices(emptySet(), setOf(id))
            env.loadPluginNow(id)

            assertEquals(id, env.pluginRemoved(id))

            assertFalse(env.isPluginRunning(id))
            assertTrue(env.platform.extensions.extensions(LIVE_EP).isEmpty())
            assertTrue(env.installedPlugins.none { it.manifest.id == id })
            assertTrue(env.pluginCatalog.manifest(id) == null)
        }
    }

    @Test
    fun `a plugin that needs one that is not loaded waits, and nothing of it runs`() {
        ApplicationEnvironment().use { env ->
            env.pluginAppeared(LiveBuild(id, "1", LiveFirstBuild::class.java.name, dependsOn = listOf("com.example.absent")))
            env.setPluginChoices(emptySet(), setOf(id))

            val r = env.loadPluginNow(id)

            assertIs<ApplicationEnvironment.PluginReload.Failed>(r)
            assertTrue(r.reason.contains("com.example.absent"))
            assertFalse(env.isPluginRunning(id))
            assertTrue(env.platform.extensions.extensions(LIVE_EP).isEmpty())
        }
    }

    @Test
    fun `a plugin claiming a built-in's id is not taken up`() {
        ApplicationEnvironment().use { env ->
            val builtIn = env.pluginCatalog.all.first { !env.pluginCatalog.isExternal(it.id) }.id
            val r = env.pluginAppeared(LiveBuild(builtIn, "1", LiveFirstBuild::class.java.name, pkg = "com.example.impostor"))

            assertIs<ApplicationEnvironment.PluginArrival.NeedsRestart>(r)
            assertTrue(env.installedPlugins.none { it.origin.label == "com.example.impostor" })
        }
    }
}
