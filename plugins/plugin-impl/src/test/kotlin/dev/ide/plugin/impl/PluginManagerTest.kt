package dev.ide.plugin.impl

import dev.ide.platform.ExtensionPoint
import dev.ide.platform.PluginId
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceLookup
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

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

    /** Captures the [PluginRegistration.dataDir] a plugin is handed. */
    private class DataDirPlugin(id: String) : Plugin {
        override val manifest = PluginManifest(id = id, name = id)
        lateinit var dir: Path
            private set

        override fun register(reg: PluginRegistration) {
            dir = reg.dataDir
        }
    }

    @Test
    fun `each plugin gets its own data directory, created on read`() {
        val root = Files.createTempDirectory("plugin-data-root")
        try {
            val a = DataDirPlugin("alpha")
            val b = DataDirPlugin("beta")
            PluginManager(ExtensionRegistryImpl(), dataRoot = root).loadAll(listOf(a, b))

            assertEquals(root.resolve("alpha"), a.dir)
            assertEquals(root.resolve("beta"), b.dir)
            assertTrue(Files.isDirectory(a.dir), "reading dataDir must create it, so a plugin can write at once")
            assertTrue(Files.isDirectory(b.dir))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plugin id that is not a safe path name cannot escape the data root`() {
        val root = Files.createTempDirectory("plugin-data-root")
        try {
            // An id comes from a manifest its author wrote, so it is reduced rather than trusted.
            val evil = DataDirPlugin("../../etc")
            PluginManager(ExtensionRegistryImpl(), dataRoot = root).loadAll(listOf(evil))
            assertTrue(
                evil.dir.normalize().startsWith(root),
                "dataDir must stay under the root, was ${evil.dir}",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Captures what a plugin can find out about its own packaged native libraries. */
    private class NativeLibPlugin(id: String) : Plugin {
        override val manifest = PluginManifest(id = id, name = id)
        var dir: Path? = null
            private set
        var resolved: Path? = null
            private set
        var escaped: Path? = null
            private set

        override fun register(reg: PluginRegistration) {
            dir = reg.nativeLibraryDir
            resolved = reg.nativeLibrary("clang")
            escaped = reg.nativeLibrary("../../other/clang")
        }
    }

    /**
     * A plugin that ships a compiler has to be able to find it: an executable it packages is unpacked outside
     * its writable storage, and since Android 10 that is the only place it may run one from. Without this a
     * plugin could package a binary and had no supported way to name its path.
     */
    @Test
    fun `a plugin resolves its own packaged native library by plain name`() {
        val libs = Files.createTempDirectory("plugin-native-libs")
        try {
            val packaged = libs.resolve(System.mapLibraryName("clang"))
            Files.writeString(packaged, "not really a compiler")
            val p = NativeLibPlugin("com.example.ndk")
            PluginManager(ExtensionRegistryImpl(), nativeLibraryDirs = mapOf("com.example.ndk" to libs))
                .loadAll(listOf(p))

            assertEquals(libs, p.dir)
            assertEquals(packaged, p.resolved, "the lib prefix and the extension are the platform's to add")
            // A name carrying a path is refused rather than reduced to `clang`, so no spelling of it reaches
            // a sibling plugin's directory, and a caller that passed a path gets nothing rather than a
            // confidently wrong file.
            assertNull(p.escaped)
        } finally {
            libs.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a plugin whose host unpacked no native libraries is handed none`() {
        val p = NativeLibPlugin("com.example.plain")
        PluginManager(ExtensionRegistryImpl()).loadAll(listOf(p))

        assertNull(p.dir, "a built-in and a standalone test have no such directory, and must not invent one")
        assertNull(p.resolved)
    }

    @Test
    fun `a library the plugin did not package for this device answers null`() {
        val libs = Files.createTempDirectory("plugin-native-libs")
        try {
            val p = NativeLibPlugin("com.example.ndk")
            PluginManager(ExtensionRegistryImpl(), nativeLibraryDirs = mapOf("com.example.ndk" to libs))
                .loadAll(listOf(p))

            assertEquals(libs, p.dir, "the directory exists even when this ABI's binaries do not")
            assertNull(p.resolved, "a missing library is an unsupported device, not a path to a missing file")
        } finally {
            libs.toFile().deleteRecursively()
        }
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
    fun `appServices resolves a service another plugin registered`() {
        val reg = ExtensionRegistryImpl()
        val key = ServiceKey<String>("test.shared")
        val publisher = object : Plugin {
            override val manifest = PluginManifest(id = "publisher", name = "publisher")
            override fun register(reg: PluginRegistration) {
                reg.service(key, ServiceScopeLevel.APPLICATION) { "shared-instance" }
            }
        }
        // The documented pattern: keep the lookup, resolve at callback time rather than during load.
        val consumer = object : Plugin {
            lateinit var services: ServiceLookup
            override val manifest =
                PluginManifest(id = "consumer", name = "consumer", dependsOn = listOf("publisher"))

            override fun register(reg: PluginRegistration) {
                services = reg.appServices
            }
        }
        PluginManager(reg, appServices = ApplicationContainer(reg))
            .loadAll(listOf(consumer, publisher))

        assertEquals("shared-instance", consumer.services.getService(key))
        // A key nothing registered is an answer, not a failure: the consumer falls back.
        assertNull(consumer.services.getServiceOrNull(ServiceKey<String>("test.absent")))
    }

    @Test
    fun `appServices is empty when the host wired no container`() {
        val reg = ExtensionRegistryImpl()
        val key = ServiceKey<String>("test.shared")
        val plugin = object : Plugin {
            lateinit var services: ServiceLookup
            override val manifest = PluginManifest(id = "solo", name = "solo")
            override fun register(reg: PluginRegistration) {
                reg.service(key, ServiceScopeLevel.APPLICATION) { "unreachable" }
                services = reg.appServices
            }
        }
        PluginManager(reg).loadAll(listOf(plugin))

        // The descriptor is registered, but with no container there is nothing to resolve it against.
        assertNull(plugin.services.getServiceOrNull(key))
        assertFailsWith<IllegalStateException> { plugin.services.getService(key) }
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

    @Test
    fun `a plugin whose register throws leaves nothing registered`() {
        val reg = ExtensionRegistryImpl()
        val mgr = PluginManager(reg)
        assertFailsWith<IllegalStateException> { mgr.load(HalfRegisteringPlugin("bad")) }
        // The contribution it made before throwing must not survive as an untracked registration.
        assertTrue(reg.extensions(EP).isEmpty(), "expected no leftovers, got ${reg.extensions(EP)}")
        assertTrue(mgr.loadedIds.isEmpty())
    }

    @Test
    fun `the tolerant load reports a failure and skips its dependents`() {
        val reg = ExtensionRegistryImpl()
        val mgr = PluginManager(reg)
        val failures = mutableListOf<String>()
        mgr.loadAll(
            listOf(
                FakePlugin("ok"),
                HalfRegisteringPlugin("bad"),
                FakePlugin("needs-bad", dependsOn = listOf("bad")),
            )
        ) { plugin, _ -> failures += plugin.manifest.id }

        assertEquals(listOf("bad", "needs-bad"), failures)
        assertEquals(listOf(PluginId("ok")), mgr.loadedIds)
        assertEquals(listOf("ok-impl"), reg.extensions(EP))
    }
}

/** Contributes, then throws: the shape of a third-party plugin that fails part-way through `register`. */
private class HalfRegisteringPlugin(id: String) : Plugin {
    override val manifest = PluginManifest(id = id, name = id)

    override fun register(reg: PluginRegistration) {
        reg.register(EP, "$manifest-partial")
        throw IllegalStateException("register failed")
    }
}


/** Records what the registrar reported as the running IDE's version. */
private class HostVersionPlugin(id: String = "hv") : Plugin {
    override val manifest = PluginManifest(id = id, name = id)
    var seen: String? = null
    var registered = false

    override fun register(reg: PluginRegistration) {
        seen = reg.hostVersion
        registered = true
    }
}

class PluginRegistrationHostVersionTest {

    @Test
    fun `a plugin sees the host version the manager was built with`() {
        val plugin = HostVersionPlugin()
        PluginManager(ExtensionRegistryImpl(), hostVersion = "3.12.0").loadAll(listOf(plugin))
        assertTrue(plugin.registered, "the plugin must have been registered")
        assertEquals("3.12.0", plugin.seen)
    }

    @Test
    fun `the host version is null when the host supplied none`() {
        val plugin = HostVersionPlugin()
        PluginManager(ExtensionRegistryImpl()).loadAll(listOf(plugin))
        assertTrue(plugin.registered, "the plugin must have been registered")
        assertNull(plugin.seen, "a host with no version to report must not invent one")
    }
}
