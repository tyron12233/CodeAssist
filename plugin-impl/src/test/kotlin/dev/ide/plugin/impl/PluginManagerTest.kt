package dev.ide.plugin.impl

import dev.ide.platform.ExtensionPoint
import dev.ide.platform.PluginId
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val EP = ExtensionPoint<String>("test.ep")

/** A fake plugin that contributes one string to [EP], either directly (tracked Disposable) or through the
 *  [PluginRegistration.contributeVia] facade (discarded Disposable — only `unregisterAll` can remove it). */
private class FakePlugin(
    id: String,
    dependsOn: List<String> = emptyList(),
    private val loadOrder: MutableList<String>? = null,
    private val viaFacade: Boolean = false,
) : Plugin {
    override val manifest = PluginManifest(id = id, name = id, dependsOn = dependsOn)
    val contribution = "$id-impl"

    override fun register(reg: PluginRegistration) {
        loadOrder?.add(manifest.id)
        if (viaFacade) reg.contributeVia { ext, pid -> ext.register(EP, contribution, pid) }
        else reg.register(EP, contribution)
    }
}

class PluginManagerTest {

    @Test
    fun `loads in dependency order regardless of declaration order`() {
        val reg = ExtensionRegistryImpl()
        val order = mutableListOf<String>()
        // Declared dependent-first; topo-sort must reorder 'a' before 'b'.
        PluginManager(reg).loadAll(
            listOf(
                FakePlugin("b", dependsOn = listOf("a"), loadOrder = order),
                FakePlugin("a", loadOrder = order),
            )
        )
        assertEquals(listOf("a", "b"), order)
        assertEquals(listOf("a-impl", "b-impl"), reg.extensions(EP))
    }

    @Test
    fun `unload removes exactly the plugin's own contributions`() {
        val reg = ExtensionRegistryImpl()
        val mgr = PluginManager(reg)
        mgr.loadAll(listOf(FakePlugin("a"), FakePlugin("b")))
        assertEquals(listOf("a-impl", "b-impl"), reg.extensions(EP))

        mgr.unload(PluginId("a"))
        assertEquals(listOf("b-impl"), reg.extensions(EP))
        assertEquals(listOf(PluginId("b")), mgr.loadedIds)
    }

    @Test
    fun `unload sweeps facade contributions whose Disposable was discarded`() {
        val reg = ExtensionRegistryImpl()
        val mgr = PluginManager(reg)
        mgr.loadAll(listOf(FakePlugin("c", viaFacade = true)))
        assertEquals(listOf("c-impl"), reg.extensions(EP))

        mgr.unload(PluginId("c"))
        assertTrue(reg.extensions(EP).isEmpty())
    }

    @Test
    fun `service() registers an attributed descriptor on SERVICE_EP and unloads with the plugin`() {
        val reg = ExtensionRegistryImpl()
        val key = ServiceKey<String>("test.svc")
        val plugin = object : Plugin {
            override val manifest = PluginManifest(id = "svc", name = "svc")
            override fun register(reg: PluginRegistration) {
                reg.service(key, ServiceScopeLevel.WORKSPACE) { "hello" }
            }
        }
        val mgr = PluginManager(reg)
        mgr.loadAll(listOf(plugin))

        val descriptors = reg.extensions(SERVICE_EP)
        assertEquals(1, descriptors.size)
        assertEquals("test.svc", descriptors[0].key.id)
        assertEquals(ServiceScopeLevel.WORKSPACE, descriptors[0].level)
        assertEquals(PluginId("svc"), descriptors[0].plugin)

        mgr.unload(PluginId("svc"))
        assertTrue(reg.extensions(SERVICE_EP).isEmpty())
    }

    @Test
    fun `unloadAll clears every contribution and the loaded set`() {
        val reg = ExtensionRegistryImpl()
        val mgr = PluginManager(reg)
        mgr.loadAll(listOf(FakePlugin("a"), FakePlugin("b", dependsOn = listOf("a"))))
        mgr.unloadAll()
        assertTrue(reg.extensions(EP).isEmpty())
        assertTrue(mgr.loadedIds.isEmpty())
    }

    @Test
    fun `a missing dependency throws`() {
        assertFailsWith<IllegalArgumentException> {
            PluginManager(ExtensionRegistryImpl())
                .loadAll(listOf(FakePlugin("a", dependsOn = listOf("nope"))))
        }
    }

    @Test
    fun `a dependency cycle throws`() {
        assertFailsWith<IllegalArgumentException> {
            PluginManager(ExtensionRegistryImpl()).loadAll(
                listOf(
                    FakePlugin("a", dependsOn = listOf("b")),
                    FakePlugin("b", dependsOn = listOf("a")),
                )
            )
        }
    }

    @Test
    fun `a duplicate plugin id throws`() {
        assertFailsWith<IllegalArgumentException> {
            PluginManager(ExtensionRegistryImpl()).loadAll(listOf(FakePlugin("a"), FakePlugin("a")))
        }
    }
}
