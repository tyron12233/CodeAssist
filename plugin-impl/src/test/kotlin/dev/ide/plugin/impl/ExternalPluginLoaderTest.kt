package dev.ide.plugin.impl

import dev.ide.platform.ExtensionPoint
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EXT_EP = ExtensionPoint<String>("test.external.ep")

/** Instantiated reflectively by the loader, exactly as an installed plugin's entry point is. */
class ExternalEntryPoint : Plugin {
    // Deliberately different from the manifest the host discovers: the host's must win.
    override val manifest = PluginManifest(id = "self-declared", name = "Self Declared", essential = true)

    override fun register(reg: PluginRegistration) {
        reg.register(EXT_EP, "external-impl")
    }
}

/** An entry point that fails on construction, as a plugin built against the wrong host would. */
class ThrowingEntryPoint : Plugin {
    init {
        throw IllegalStateException("boom")
    }

    override val manifest = PluginManifest(id = "throwing", name = "Throwing")
    override fun register(reg: PluginRegistration) {}
}

/** An entry point named in a manifest that does not implement the SPI. */
class NotAPlugin

private class FakeDiscovered(
    override val manifest: PluginManifest,
    private val loader: ClassLoader? = FakeDiscovered::class.java.classLoader,
) : DiscoveredPlugin {
    override val origin = PluginOrigin("test", "com.example.plugin", signature = "ab12")
    override fun classLoader(): ClassLoader = loader ?: error("no classloader for this plugin")
}

private fun manifest(
    id: String = "com.example.plugin",
    apiVersion: Int = 1,
    entryPoints: List<String> = listOf(ExternalEntryPoint::class.java.name),
    uiEntryPoints: List<String> = emptyList(),
    minHostVersion: String? = null,
) = PluginManifest(
    id = id, name = "Example", version = "1.0.0", apiVersion = apiVersion,
    entryPoints = entryPoints, uiEntryPoints = uiEntryPoints, minHostVersion = minHostVersion, trusted = false,
)

class ExternalPluginLoaderTest {

    @Test
    fun `loads an entry point and registers its contributions`() {
        val result = ExternalPluginLoader().load(FakeDiscovered(manifest()))
        assertTrue(result is ExternalPluginLoader.Result.Loaded, "expected a load, got $result")

        val registry = ExtensionRegistryImpl()
        PluginManager(registry).load(result.plugin)
        assertEquals(listOf("external-impl"), registry.extensions(EXT_EP))
    }

    @Test
    fun `the discovered manifest wins over the entry point's own`() {
        val result = ExternalPluginLoader().load(FakeDiscovered(manifest()))
        assertTrue(result is ExternalPluginLoader.Result.Loaded)
        // The class declares id 'self-declared' and essential = true; neither may reach the manager.
        assertEquals("com.example.plugin", result.plugin.manifest.id)
        assertTrue(!result.plugin.manifest.essential)
    }

    @Test
    fun `rejects a plugin built against a different plugin API`() {
        val result = ExternalPluginLoader(hostApiVersion = 2).load(FakeDiscovered(manifest(apiVersion = 1)))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertTrue("API" in result.reason, result.reason)
    }

    @Test
    fun `rejects a plugin that needs a newer host`() {
        val loader = ExternalPluginLoader(hostVersion = "3.11.0")
        val result = loader.load(FakeDiscovered(manifest(minHostVersion = "3.12.0")))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertTrue("3.12.0" in result.reason, result.reason)
    }

    @Test
    fun `accepts a plugin whose minimum host version is met`() {
        val loader = ExternalPluginLoader(hostVersion = "3.11.0-beta2")
        val result = loader.load(FakeDiscovered(manifest(minHostVersion = "3.9.6")))
        assertTrue(result is ExternalPluginLoader.Result.Loaded, "expected a load, got $result")
    }

    @Test
    fun `reports a missing entry-point class instead of throwing`() {
        val result = ExternalPluginLoader().load(FakeDiscovered(manifest(entryPoints = listOf("com.example.Gone"))))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertTrue("com.example.Gone" in result.reason, result.reason)
    }

    @Test
    fun `reports an entry point that does not implement the SPI`() {
        val result = ExternalPluginLoader()
            .load(FakeDiscovered(manifest(entryPoints = listOf(NotAPlugin::class.java.name))))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertTrue("does not implement" in result.reason, result.reason)
    }

    @Test
    fun `reports an entry point that throws on construction`() {
        val result = ExternalPluginLoader()
            .load(FakeDiscovered(manifest(entryPoints = listOf(ThrowingEntryPoint::class.java.name))))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertTrue("boom" in result.reason, result.reason)
    }

    @Test
    fun `reports a manifest with no entry point`() {
        val result = ExternalPluginLoader().load(FakeDiscovered(manifest(entryPoints = emptyList())))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertEquals("declares no entry point", result.reason)
    }

    @Test
    fun `carries the classloader out, for the host to load the plugin's other facets from`() {
        val result = ExternalPluginLoader().load(FakeDiscovered(manifest()))
        assertTrue(result is ExternalPluginLoader.Result.Loaded)
        // Must be the loader the engine facet came off: loading the UI facet from anywhere else would put a
        // plugin's two halves in different classloaders, where they cannot see each other.
        assertEquals(FakeDiscovered::class.java.classLoader, result.classLoader)
    }

    @Test
    fun `a plugin declaring only a UI facet loads with an inert engine facet`() {
        val result = ExternalPluginLoader().load(
            FakeDiscovered(
                manifest(entryPoints = emptyList(), uiEntryPoints = listOf("com.example.SomeUiPlugin")),
            )
        )
        assertTrue(result is ExternalPluginLoader.Result.Loaded, "expected a load, got $result")
        // It keeps its place in the load order and its identity; register() simply has nothing to do. The UI
        // facet itself is instantiated by the host, which is where the Compose-side types are visible.
        assertEquals("com.example.plugin", result.plugin.manifest.id)
        val registry = ExtensionRegistryImpl()
        PluginManager(registry).load(result.plugin)
        assertTrue(registry.extensions(EXT_EP).isEmpty())
    }

    @Test
    fun `reports a source whose classloader cannot be built`() {
        val result = ExternalPluginLoader().load(FakeDiscovered(manifest(), loader = null))
        assertTrue(result is ExternalPluginLoader.Result.Failed)
        assertTrue("no classloader" in result.reason, result.reason)
    }

    @Test
    fun `an installed plugin cannot declare itself essential`() {
        val installed = PluginManifest(id = "com.example.plugin", name = "Example", essential = true)
        val catalog = PluginCatalog(
            all = listOf(PluginManifest(id = "platform", name = "Platform", essential = true), installed),
            disabledIds = setOf("com.example.plugin"),
            externalIds = setOf("com.example.plugin"),
        )
        assertTrue(catalog.isEssential("platform"))
        assertTrue(!catalog.isEssential("com.example.plugin"))
        assertTrue(!catalog.isEnabled("com.example.plugin"), "a disabled installed plugin must stay off")
        assertTrue(catalog.isExternal("com.example.plugin"))
        assertNull(catalog.manifest("nope"))
    }
}
