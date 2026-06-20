package dev.ide.platform.impl

import dev.ide.platform.Disposable
import dev.ide.platform.PluginId
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceContainer
import dev.ide.platform.ServiceCycleException
import dev.ide.platform.ServiceDescriptor
import dev.ide.platform.ServiceFactory
import dev.ide.platform.ServiceKey
import dev.ide.platform.ServiceScopeLevel.APPLICATION
import dev.ide.platform.ServiceScopeLevel.MODULE
import dev.ide.platform.ServiceScopeLevel.WORKSPACE
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceContainerTest {

    private class Counter { val n = AtomicInteger(0) }
    private val KEY = ServiceKey<Counter>("test.counter")

    @Test
    fun instantiatesLazilyAndOnce() {
        val builds = AtomicInteger(0)
        val c = ServiceContainerImpl(APPLICATION, null, null)
        c.registerService(KEY) { builds.incrementAndGet(); Counter() }
        assertEquals(0, builds.get(), "factory should not run before first getService")
        val a = c.getService(KEY)
        val b = c.getService(KEY)
        assertSame(a, b)
        assertEquals(1, builds.get(), "factory runs exactly once")
    }

    @Test
    fun unresolvedThrowsButOrNullReturnsNull() {
        val c = ServiceContainerImpl(WORKSPACE, null, null)
        assertFailsWith<IllegalStateException> { c.getService(KEY) }
        assertNull(c.getServiceOrNull(KEY))
    }

    @Test
    fun fallsBackToParentAndSharesTheSingleton() {
        val app = ServiceContainerImpl(APPLICATION, null, null)
        app.registerService(KEY) { Counter() }
        val workspace = ServiceContainerImpl(WORKSPACE, app, "workspace")
        val moduleA = ServiceContainerImpl(MODULE, workspace, "a")
        val moduleB = ServiceContainerImpl(MODULE, workspace, "b")
        // Both modules forward up to the one app-scoped instance.
        assertSame(app.getService(KEY), moduleA.getService(KEY))
        assertSame(moduleA.getService(KEY), moduleB.getService(KEY))
    }

    @Test
    fun factoryReachesScopeObjectAndParentServices() {
        val SDK = ServiceKey<String>("test.sdk")
        val ANALYZER = ServiceKey<String>("test.analyzer")
        val app = ServiceContainerImpl(APPLICATION, null, null)
        app.registerService(SDK) { "sdk-23" }
        val workspace = ServiceContainerImpl(WORKSPACE, app, "ws")
        val module = ServiceContainerImpl(MODULE, workspace, "feature")
        module.registerService(ANALYZER) { "analyzer(${scopeObject}, ${getService(SDK)})" }
        assertEquals("analyzer(feature, sdk-23)", module.getService(ANALYZER))
    }

    @Test
    fun programmaticOverridesEpDescriptor() {
        val registry = ExtensionRegistryImpl()
        registry.register(
            SERVICE_EP,
            ServiceDescriptor(KEY, WORKSPACE, ServiceFactory { Counter().also { it.n.set(1) } }, PluginId("ep")),
            PluginId("ep"),
        )
        val c = ServiceContainerImpl(WORKSPACE, null, null) { registry.extensions(SERVICE_EP) }
        // EP descriptor alone resolves.
        assertEquals(1, c.getService(KEY).n.get())

        val c2 = ServiceContainerImpl(WORKSPACE, null, null) { registry.extensions(SERVICE_EP) }
        c2.registerService(KEY) { Counter().also { it.n.set(99) } }
        assertEquals(99, c2.getService(KEY).n.get(), "programmatic registration wins over the EP descriptor")
    }

    @Test
    fun duplicateEpDescriptorsAtSameLevelFail() {
        val registry = ExtensionRegistryImpl()
        repeat(2) {
            registry.register(SERVICE_EP, ServiceDescriptor(KEY, WORKSPACE, ServiceFactory { Counter() }, PluginId("p$it")), PluginId("p$it"))
        }
        val c = ServiceContainerImpl(WORKSPACE, null, null) { registry.extensions(SERVICE_EP) }
        assertFailsWith<IllegalStateException> { c.getService(KEY) }
    }

    @Test
    fun detectsDependencyCycle() {
        val A = ServiceKey<String>("test.a")
        val B = ServiceKey<String>("test.b")
        val c = ServiceContainerImpl(WORKSPACE, null, null)
        c.registerService(A) { "a:" + getService(B) }
        c.registerService(B) { "b:" + getService(A) }
        val ex = assertFailsWith<ServiceCycleException> { c.getService(A) }
        assertTrue(ex.chain.contains("test.a") && ex.chain.contains("test.b"))
    }

    @Test
    fun disposesServicesLifoWithTheContainer() {
        val order = ArrayList<String>()
        class S(val name: String) : Disposable { override fun dispose() { synchronized(order) { order.add(name) } } }
        val first = ServiceKey<S>("test.first")
        val second = ServiceKey<S>("test.second")
        val c = ServiceContainerImpl(WORKSPACE, null, null)
        c.registerService(first) { S("first") }
        c.registerService(second) { S("second") }
        c.getService(first); c.getService(second)
        c.dispose()
        assertEquals(listOf("second", "first"), order, "LIFO teardown, last instantiated first")
    }

    @Test
    fun evictDisposesAndAllowsRebuild() {
        val builds = AtomicInteger(0)
        val disposed = AtomicInteger(0)
        class S : Disposable { init { builds.incrementAndGet() }; override fun dispose() { disposed.incrementAndGet() } }
        val key = ServiceKey<S>("test.evictable")
        val c = ServiceContainerImpl(MODULE, null, null)
        c.registerService(key) { S() }
        val a = c.getService(key)
        c.evict(key)
        assertEquals(1, disposed.get(), "evict disposes the live instance")
        val b = c.getService(key)
        assertTrue(a !== b, "a fresh instance is built after evict")
        assertEquals(2, builds.get())
        c.dispose()
        assertEquals(2, disposed.get(), "the evicted instance is not double-disposed at container dispose")
    }

    @Test
    fun concurrentDistinctKeysAllResolveExactlyOnce() {
        val n = 200
        val keys = (0 until n).map { ServiceKey<Counter>("test.k$it") }
        val builds = ConcurrentBuildCounter()
        val c: ServiceContainer = ServiceContainerImpl(WORKSPACE, null, null)
        keys.forEach { k -> c.registerService(k) { builds.bump(k.id); Counter() } }
        keys.parallelStream().forEach { repeat(5) { _ -> c.getService(it) } }
        assertTrue(keys.all { builds.count(it.id) == 1 }, "each key built exactly once under concurrency")
    }

    private class ConcurrentBuildCounter {
        private val m = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        fun bump(id: String) { m.getOrPut(id) { AtomicInteger() }.incrementAndGet() }
        fun count(id: String) = m[id]?.get() ?: 0
    }
}
