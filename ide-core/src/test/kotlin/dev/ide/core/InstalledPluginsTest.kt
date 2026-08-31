package dev.ide.core

import dev.ide.platform.ExtensionPoint
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val INSTALLED_EP = ExtensionPoint<String>("test.installed.ep")

/** Stands in for an installed plugin's entry point: instantiated reflectively off the source's classloader. */
class InstalledEntryPoint : Plugin {
    override val manifest = PluginManifest(id = "ignored", name = "Ignored")
    override fun register(reg: PluginRegistration) {
        reg.register(INSTALLED_EP, "installed-impl")
    }
}

private class FakeSource(private val plugins: List<DiscoveredPlugin>) : PluginSource {
    override val id = "fake"
    override fun discover(): List<DiscoveredPlugin> = plugins
}

private class ThrowingSource : PluginSource {
    override val id = "broken"
    override fun discover(): List<DiscoveredPlugin> = error("the package manager is unavailable")
}

private class FakePlugin(
    id: String,
    entryPoint: String = InstalledEntryPoint::class.java.name,
    dependsOn: List<String> = emptyList(),
    apiVersion: Int = 1,
) : DiscoveredPlugin {
    override val manifest = PluginManifest(
        id = id, name = id, apiVersion = apiVersion,
        dependsOn = dependsOn, entryPoints = listOf(entryPoint), trusted = false,
    )
    override val origin = PluginOrigin("fake", id, signature = "cafe")
    override fun classLoader(): ClassLoader = javaClass.classLoader
}

class InstalledPluginsTest {

    @Test
    fun `an installed plugin loads and contributes alongside the built-ins`() {
        val source = FakeSource(listOf(FakePlugin("com.example.ok", dependsOn = listOf("kotlin-language"))))
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            assertEquals(listOf("installed-impl"), env.platform.extensions.extensions(INSTALLED_EP))
            assertTrue(env.pluginCatalog.isExternal("com.example.ok"))
            assertTrue(env.pluginCatalog.isEnabled("com.example.ok"))

            val installed = env.installedPlugins.single()
            assertEquals("com.example.ok", installed.manifest.id)
            assertEquals("com.example.ok", installed.origin.label)
            assertNull(installed.error)
        }
    }

    @Test
    fun `the user's disabled set keeps an installed plugin from loading at all`() {
        val source = FakeSource(listOf(FakePlugin("com.example.off")))
        val env = ApplicationEnvironment(
            disabledPluginIds = setOf("com.example.off"),
            pluginSources = listOf(source),
        )
        env.use {
            assertTrue(env.platform.extensions.extensions(INSTALLED_EP).isEmpty())
            assertTrue(!env.pluginCatalog.isEnabled("com.example.off"))
            // Still listed, so the Plugins screen can offer it back.
            assertEquals("com.example.off", env.installedPlugins.single().manifest.id)
        }
    }

    @Test
    fun `an installed plugin that cannot load is recorded, not thrown`() {
        val source = FakeSource(
            listOf(
                FakePlugin("com.example.gone", entryPoint = "com.example.NotThere"),
                FakePlugin("com.example.stale", apiVersion = 99),
                FakePlugin("com.example.orphan", dependsOn = listOf("com.example.gone")),
                FakePlugin("com.example.ok"),
            )
        )
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            val errors = env.installedPlugins.associate { it.manifest.id to it.error }
            assertTrue(errors.getValue("com.example.gone")!!.contains("com.example.NotThere"))
            assertTrue(errors.getValue("com.example.stale")!!.contains("API"))
            assertTrue(errors.getValue("com.example.orphan")!!.contains("com.example.gone"))
            assertNull(errors.getValue("com.example.ok"))
            // The one good plugin still loaded.
            assertEquals(listOf("installed-impl"), env.platform.extensions.extensions(INSTALLED_EP))
        }
    }

    @Test
    fun `an installed plugin cannot take a built-in's id`() {
        val source = FakeSource(listOf(FakePlugin("kotlin-language")))
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            assertTrue(env.installedPlugins.isEmpty())
            assertTrue(!env.pluginCatalog.isExternal("kotlin-language"))
            assertTrue(env.pluginCatalog.isEnabled("kotlin-language"), "the built-in must be untouched")
        }
    }

    @Test
    fun `a source that throws does not stop the IDE from starting`() {
        val sources = listOf(ThrowingSource(), FakeSource(listOf(FakePlugin("com.example.ok"))))
        ApplicationEnvironment(pluginSources = sources).use { env ->
            assertEquals(listOf("com.example.ok"), env.installedPlugins.map { it.manifest.id })
            assertEquals(listOf("installed-impl"), env.platform.extensions.extensions(INSTALLED_EP))
        }
    }
}
