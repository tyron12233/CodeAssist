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

/**
 * Stands in for an installed plugin's UI facet. It registers nothing: what the environment is responsible for
 * is instantiating it off the plugin's own classloader and handing it to the shell, and the mapping from a
 * contribution to the host's registries is the bridge's job (covered in :ide-ui, where the Compose compiler
 * is available to build a real body).
 */
class InstalledUiFacet : dev.ide.plugin.ui.UiPlugin {
    override val id = "com.example.ui"
    override fun contribute(ui: dev.ide.plugin.ui.UiRegistration) {
        contributedFor += ui.pluginId
    }

    companion object {
        /** The plugin ids this facet was contributed under, so a test can see that it actually ran. */
        val contributedFor = mutableListOf<String>()
    }
}

/** A UI facet whose constructor fails, standing in for one that is broken on the user's device. */
class ThrowingUiFacet : dev.ide.plugin.ui.UiPlugin {
    init {
        error("this facet cannot be constructed")
    }

    override val id = "com.example.throwing"
    override fun contribute(ui: dev.ide.plugin.ui.UiRegistration) = Unit
}

/** Not a UI facet at all, for a manifest naming the wrong class. */
class NotAUiFacet

private class FakePlugin(
    id: String,
    entryPoint: String = InstalledEntryPoint::class.java.name,
    dependsOn: List<String> = emptyList(),
    apiVersion: Int = 1,
    entryPoints: List<String> = listOf(entryPoint),
    uiEntryPoints: List<String> = emptyList(),
) : DiscoveredPlugin {
    override val manifest = PluginManifest(
        id = id, name = id, apiVersion = apiVersion,
        dependsOn = dependsOn, entryPoints = entryPoints, uiEntryPoints = uiEntryPoints, trusted = false,
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
    fun `an installed plugin's UI facet loads off the same classloader as its engine facet`() {
        InstalledUiFacet.contributedFor.clear()
        val source = FakeSource(
            listOf(
                FakePlugin("com.example.withui", uiEntryPoints = listOf(InstalledUiFacet::class.java.name)),
            )
        )
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf("com.example.withui"),
        ).use { env ->
            // Both facets ran: the engine one contributed to its EP, the UI one is handed to the shell.
            assertEquals(listOf("installed-impl"), env.platform.extensions.extensions(INSTALLED_EP))
            val ui = env.enabledUiPlugins.single { it.id == "com.example.withui" }
            // The packaged manifest's id wins over the one the facet declares for itself.
            assertEquals("com.example.withui", ui.id)
            assertNull(env.installedPlugins.single().error)

            // Nothing has contributed yet: the shell drives that, once, when it composes.
            assertTrue(InstalledUiFacet.contributedFor.isEmpty())
            ui.contributeUi(RecordingScope(ui.id))
            assertEquals(listOf("com.example.withui"), InstalledUiFacet.contributedFor)
        }
    }

    @Test
    fun `a plugin may declare only a UI facet`() {
        val source = FakeSource(
            listOf(
                FakePlugin(
                    "com.example.uionly",
                    entryPoints = emptyList(),
                    uiEntryPoints = listOf(InstalledUiFacet::class.java.name),
                ),
            )
        )
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf("com.example.uionly"),
        ).use { env ->
            // No engine entry point is not an error: the plugin loads, holds its place in the order, and
            // contributes its UI.
            assertNull(env.installedPlugins.single().error)
            assertEquals(1, env.enabledUiPlugins.count { it.id == "com.example.uionly" })
        }
    }

    @Test
    fun `a broken UI facet is reported against the plugin, and its engine facet still loads`() {
        val source = FakeSource(
            listOf(
                FakePlugin("com.example.badui", uiEntryPoints = listOf(ThrowingUiFacet::class.java.name)),
                FakePlugin("com.example.wrongui", uiEntryPoints = listOf(NotAUiFacet::class.java.name)),
                FakePlugin("com.example.missingui", uiEntryPoints = listOf("com.example.NotThere")),
            )
        )
        ApplicationEnvironment(
            pluginSources = listOf(source),
            consentedPluginIds = setOf("com.example.badui", "com.example.wrongui", "com.example.missingui"),
        ).use { env ->
            assertTrue(env.enabledUiPlugins.none { it.id.startsWith("com.example.") }, "no UI should have loaded")
            val errors = env.installedPlugins.associate { it.manifest.id to it.error }
            for (id in listOf("com.example.badui", "com.example.wrongui", "com.example.missingui")) {
                val error = errors.getValue(id)
                assertTrue(error != null && "UI facet" in error, "expected a UI-facet reason for $id, got $error")
            }
            // The engine facets are unaffected: three plugins registered, one extension each.
            assertEquals(3, env.platform.extensions.extensions(INSTALLED_EP).size)
        }
    }

    @Test
    fun `an unconsented plugin contributes no UI either`() {
        val source = FakeSource(
            listOf(FakePlugin("com.example.quiet", uiEntryPoints = listOf(InstalledUiFacet::class.java.name)))
        )
        ApplicationEnvironment(pluginSources = listOf(source)).use { env ->
            assertTrue(
                env.enabledUiPlugins.none { it.id == "com.example.quiet" },
                "a plugin the user has not allowed must not reach the UI registries",
            )
        }
    }

    /** A [dev.ide.ui.ext.UiContributionScope] that only records, for driving a bridged facet in a test. */
    private class RecordingScope(override val pluginId: String) : dev.ide.ui.ext.UiContributionScope {
        override fun action(action: dev.ide.ui.ext.UiHostAction) = dev.ide.ui.ext.Registration {}
        override fun toolWindow(toolWindow: dev.ide.ui.ext.ToolWindowContribution) = dev.ide.ui.ext.Registration {}
        override fun screen(screen: dev.ide.ui.ext.ScreenContribution) = dev.ide.ui.ext.Registration {}
        override fun viewMode(mode: dev.ide.ui.ext.EditorViewModeContribution) = dev.ide.ui.ext.Registration {}
        override fun overlay(overlay: dev.ide.ui.ext.OverlayContribution) = dev.ide.ui.ext.Registration {}
        override fun tabDecoration(decoration: dev.ide.ui.ext.TabDecorationContribution) =
            dev.ide.ui.ext.Registration {}
        override fun treeIcon(iconId: String, icon: dev.ide.ui.icons.TreeIcon) = dev.ide.ui.ext.Registration {}
        override fun editorLanguage(profile: dev.ide.ui.ext.EditorLanguageProfile) = dev.ide.ui.ext.Registration {}
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
