package dev.ide.core

import dev.ide.platform.ExtensionPoint
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginCandidate
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import dev.ide.plugin.external.RejectedPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

private class FakeSource(private val plugins: List<PluginCandidate>) : PluginSource {
    override val id = "fake"
    override fun discover(): List<PluginCandidate> = plugins
}

private class ThrowingSource : PluginSource {
    override val id = "broken"
    override fun discover(): List<PluginCandidate> = error("the package manager is unavailable")
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
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf("com.example.ok"),
        ).use { env ->
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
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf(
                "com.example.gone", "com.example.stale", "com.example.orphan", "com.example.ok",
            ),
        ).use { env ->
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
    fun `a newly discovered plugin does not run until it is allowed to`() {
        val source = FakeSource(listOf(FakePlugin("com.example.new")))
        // No consent recorded: this is a plugin app found on the device that the user has never been asked
        // about, which is not the same as one they refused.
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            assertTrue(
                env.platform.extensions.extensions(INSTALLED_EP).isEmpty(),
                "an unconsented plugin must not have registered anything",
            )
            assertFalse(env.pluginCatalog.isEnabled("com.example.new"))
            assertTrue(env.pluginCatalog.needsConsent("com.example.new"))
            // It is still listed, so the user can be asked rather than never learning it is there.
            assertEquals(listOf("com.example.new"), env.installedPlugins.map { it.manifest.id })
        }
    }

    @Test
    fun `a refusal is not the same as never having been asked`() {
        val source = FakeSource(listOf(FakePlugin("com.example.no")))
        // A refusal is recorded as a disable, which is what stops it being asked about again.
        ApplicationEnvironment(
            disabledPluginIds = setOf("com.example.no"),
            pluginSources = listOf(source),
        ).use { env ->
            assertFalse(env.pluginCatalog.isEnabled("com.example.no"))
            assertFalse(
                env.pluginCatalog.needsConsent("com.example.no"),
                "a refused plugin must not be asked about again",
            )
        }
    }

    @Test
    fun `consent does not extend to a plugin that depends on an unconsented one`() {
        val source = FakeSource(
            listOf(
                FakePlugin("com.example.base"),
                FakePlugin("com.example.dependent", dependsOn = listOf("com.example.base")),
            )
        )
        // Allowing only the dependent would otherwise load it against a base that never ran.
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf("com.example.dependent"),
        ).use { env ->
            assertFalse(env.pluginCatalog.isEnabled("com.example.dependent"))
            assertTrue(env.platform.extensions.extensions(INSTALLED_EP).isEmpty())
        }
    }

    @Test
    fun `an installed plugin cannot take a built-in's id`() {
        val source = FakeSource(listOf(FakePlugin("kotlin-language")))
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            assertTrue(env.installedPlugins.isEmpty())
            assertTrue(!env.pluginCatalog.isExternal("kotlin-language"))
            assertTrue(env.pluginCatalog.isEnabled("kotlin-language"), "the built-in must be untouched")
            // The clash is reported against the plugin that caused it, not dropped in silence.
            val rejected = env.rejectedPlugins.single()
            assertEquals("kotlin-language", rejected.origin.label)
            assertTrue(rejected.reason.contains("already in use"), rejected.reason)
        }
    }

    @Test
    fun `two plugins whose ids differ only in case cannot both load`() {
        // Both ids are legal. Keeping both would put two rows in the Plugins screen that read identically.
        val source = FakeSource(listOf(FakePlugin("com.example.Dup"), FakePlugin("com.example.dup")))
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            assertEquals(listOf("com.example.Dup"), env.installedPlugins.map { it.manifest.id })
            val rejected = env.rejectedPlugins.single()
            assertEquals("com.example.dup", rejected.origin.label)
            assertTrue(rejected.reason.contains("already in use"), rejected.reason)
        }
    }

    @Test
    fun `a plugin whose manifest could not be read is still listed, with its reason`() {
        val unreadable = RejectedPlugin(
            origin = PluginOrigin("fake", "com.example.broken"),
            reason = "plugin id 'com.Example.Broken' must be lowercase letters, digits, '.', '-' or '_'",
            name = "Broken Plugin",
        )
        ApplicationEnvironment(pluginSources = listOf(FakeSource(listOf(unreadable)))).use { env ->
            // It has no manifest, so it is neither loaded nor a candidate for enable/disable.
            assertTrue(env.installedPlugins.isEmpty())
            assertTrue(env.pluginCatalog.all.none { it.id == "com.example.broken" })
            assertTrue(env.platform.extensions.extensions(INSTALLED_EP).isEmpty())

            val rejected = env.rejectedPlugins.single()
            assertEquals("Broken Plugin", rejected.name)
            assertEquals("com.example.broken", rejected.origin.label)
            assertTrue(rejected.reason.contains("lowercase"), rejected.reason)
        }
    }

    @Test
    fun `an unreadable plugin does not stop a good one from loading`() {
        val source = FakeSource(
            listOf(
                RejectedPlugin(PluginOrigin("fake", "com.example.broken"), reason = "not valid TOML"),
                FakePlugin("com.example.ok"),
            )
        )
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf("com.example.ok"),
        ).use { env ->
            assertEquals(listOf("com.example.ok"), env.installedPlugins.map { it.manifest.id })
            assertEquals(listOf("installed-impl"), env.platform.extensions.extensions(INSTALLED_EP))
            assertEquals(listOf("com.example.broken"), env.rejectedPlugins.map { it.origin.label })
            // With no app label to read, the row falls back to the package name.
            assertEquals("com.example.broken", env.rejectedPlugins.single().name)
        }
    }

    @Test
    fun `a source that throws does not stop the IDE from starting`() {
        val sources = listOf(ThrowingSource(), FakeSource(listOf(FakePlugin("com.example.ok"))))
        ApplicationEnvironment(
            pluginSources = sources,
            consentedPluginIds = setOf("com.example.ok"),
        ).use { env ->
            assertEquals(listOf("com.example.ok"), env.installedPlugins.map { it.manifest.id })
            assertEquals(listOf("installed-impl"), env.platform.extensions.extensions(INSTALLED_EP))
        }
    }
}
