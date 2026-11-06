package dev.ide.core

import dev.ide.platform.ExtensionPoint
import dev.ide.plugin.PLUGIN_API_VERSION
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginCandidate
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RELOAD_EP = ExtensionPoint<String>("test.reload.ep")

/** The build of a plugin that is loaded first. */
class ReloadFirstBuild : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(RELOAD_EP, "first")
    }

    override fun dispose() {
        disposed++
    }

    companion object {
        var disposed = 0
    }
}

/** The new build that replaces it. */
class ReloadSecondBuild : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(RELOAD_EP, "second")
    }
}

/** A new build whose register throws after contributing something. */
class ReloadBrokenBuild : Plugin {
    override fun register(reg: PluginRegistration) {
        reg.register(RELOAD_EP, "broken-partial")
        error("the new build is broken")
    }
}

/** A UI facet for the new build. */
class ReloadUiFacet : dev.ide.plugin.ui.UiPlugin {
    override val id = "com.example.reload"
    override fun contribute(ui: dev.ide.plugin.ui.UiRegistration) = Unit
}

private class Build(
    id: String,
    version: String,
    entryPoint: String,
    uiEntryPoints: List<String> = emptyList(),
    dependsOn: List<String> = emptyList(),
) : DiscoveredPlugin {
    override val manifest = PluginManifest(
        id = id, name = id, version = version, apiVersion = PLUGIN_API_VERSION, dependsOn = dependsOn,
        entryPoints = listOf(entryPoint), uiEntryPoints = uiEntryPoints, trusted = false,
    )
    override val origin = PluginOrigin("sandbox", "$id-$version")
    override fun classLoader(): ClassLoader = javaClass.classLoader
}

private class OneBuildSource(private val build: DiscoveredPlugin) : PluginSource {
    override val id = "sandbox"
    override fun discover(): List<PluginCandidate> = listOf(build)
}

class PluginReloadTest {
    private val id = "com.example.reload"

    private fun env() = ApplicationEnvironment(
        pluginSources = listOf(OneBuildSource(Build(id, "1", ReloadFirstBuild::class.java.name))),
        consentedPluginIds = setOf(id),
    )

    @Test
    fun `a new build replaces the old one in place`() {
        env().use { env ->
            assertEquals(listOf("first"), env.platform.extensions.extensions(RELOAD_EP))
            val disposedBefore = ReloadFirstBuild.disposed

            val r = env.reloadInstalledPlugin(
                Build(id, "2", ReloadSecondBuild::class.java.name, uiEntryPoints = listOf(ReloadUiFacet::class.java.name)),
            )

            assertIs<ApplicationEnvironment.PluginReload.Reloaded>(r)
            assertEquals(listOf("second"), env.platform.extensions.extensions(RELOAD_EP))
            assertEquals(disposedBefore + 1, ReloadFirstBuild.disposed, "the old build is disposed")
            val row = env.installedPlugins.single()
            assertEquals("2", row.manifest.version)
            assertNull(row.error)
            assertNotNull(r.ui, "the new build's UI facet comes back for the caller to swap in")
            assertEquals(listOf(id), env.enabledUiPlugins.filter { it.id == id }.map { it.id })
            assertTrue(id in env.externalPluginLoaders)
        }
    }

    @Test
    fun `a new build whose register throws leaves nothing of either build`() {
        env().use { env ->
            val r = env.reloadInstalledPlugin(Build(id, "2", ReloadBrokenBuild::class.java.name))

            assertIs<ApplicationEnvironment.PluginReload.Failed>(r)
            assertTrue(r.unloaded, "the old build is gone, so only a fresh start brings a working plugin back")
            assertTrue(env.platform.extensions.extensions(RELOAD_EP).isEmpty())
            assertTrue(env.installedPlugins.single().error!!.contains("broken"))
            assertTrue(env.enabledUiPlugins.none { it.id == id })
        }
    }

    @Test
    fun `a build that cannot be instantiated leaves the old one running`() {
        env().use { env ->
            val r = env.reloadInstalledPlugin(Build(id, "2", "com.example.NotThere"))

            assertIs<ApplicationEnvironment.PluginReload.Failed>(r)
            assertFalse(r.unloaded)
            assertEquals(listOf("first"), env.platform.extensions.extensions(RELOAD_EP))
            assertEquals("1", env.installedPlugins.single().manifest.version)
        }
    }

    @Test
    fun `a different plugin is not a new build of the loaded one`() {
        env().use { env ->
            val r = env.reloadInstalledPlugin(Build("com.example.other", "1", ReloadSecondBuild::class.java.name))

            assertIs<ApplicationEnvironment.PluginReload.Failed>(r)
            assertFalse(r.unloaded)
            assertEquals(listOf("first"), env.platform.extensions.extensions(RELOAD_EP))
        }
    }

    @Test
    fun `a new dependency that is not loaded is refused before anything is unloaded`() {
        env().use { env ->
            val r = env.reloadInstalledPlugin(
                Build(id, "2", ReloadSecondBuild::class.java.name, dependsOn = listOf("com.example.absent")),
            )

            assertIs<ApplicationEnvironment.PluginReload.Failed>(r)
            assertFalse(r.unloaded)
            assertTrue(r.reason.contains("com.example.absent"))
            assertEquals(listOf("first"), env.platform.extensions.extensions(RELOAD_EP))
        }
    }

    @Test
    fun `reloading twice keeps exactly one build registered`() {
        env().use { env ->
            env.reloadInstalledPlugin(Build(id, "2", ReloadSecondBuild::class.java.name))
            env.reloadInstalledPlugin(Build(id, "3", ReloadFirstBuild::class.java.name))
            assertEquals(listOf("first"), env.platform.extensions.extensions(RELOAD_EP))
            assertEquals("3", env.installedPlugins.single().manifest.version)
        }
    }
}
