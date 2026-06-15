package dev.ide.platform.impl

import dev.ide.platform.ExtensionPoint
import dev.ide.platform.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionRegistryTest {

    fun interface Greeter {
        fun greet(): String
    }

    private val ep = ExtensionPoint<Greeter>("test.greeter")
    private val plugin = PluginId("test")

    @Test
    fun keepsRegistrationOrder() {
        val reg = ExtensionRegistryImpl()
        reg.register(ep, Greeter { "hi" }, plugin)
        reg.register(ep, Greeter { "yo" }, plugin)
        assertEquals(listOf("hi", "yo"), reg.extensions(ep).map { it.greet() })
    }

    @Test
    fun disposeRemovesOnlyThatContribution() {
        val reg = ExtensionRegistryImpl()
        val first = reg.register(ep, Greeter { "a" }, plugin)
        reg.register(ep, Greeter { "b" }, plugin)
        first.dispose()
        assertEquals(listOf("b"), reg.extensions(ep).map { it.greet() })
    }

    @Test
    fun distinctPointsAreIsolated() {
        val reg = ExtensionRegistryImpl()
        val other = ExtensionPoint<Greeter>("test.other")
        reg.register(ep, Greeter { "x" }, plugin)
        assertTrue(reg.extensions(other).isEmpty())
        assertEquals(listOf("x"), reg.extensions(ep).map { it.greet() })
    }

    @Test
    fun samePointIdSharesTheChannel() {
        // A producer and a consumer constructing their own ExtensionPoint with the same id agree.
        val reg = ExtensionRegistryImpl()
        reg.register(ExtensionPoint<Greeter>("test.greeter"), Greeter { "p" }, plugin)
        assertEquals(listOf("p"), reg.extensions(ExtensionPoint<Greeter>("test.greeter")).map { it.greet() })
    }

    @Test
    fun unknownPointIsEmpty() {
        assertTrue(ExtensionRegistryImpl().extensions(ep).isEmpty())
    }

    @Test
    fun unregisterAllDropsAPluginsContributions() {
        val reg = ExtensionRegistryImpl()
        val other = PluginId("other")
        reg.register(ep, Greeter { "keep" }, other)
        reg.register(ep, Greeter { "drop" }, plugin)
        reg.unregisterAll(plugin)
        assertEquals(listOf("keep"), reg.extensions(ep).map { it.greet() })
    }

    @Test
    fun concurrentRegistrationIsSafe() {
        val reg = ExtensionRegistryImpl()
        val n = 500
        (1..n).toList().parallelStream().forEach { reg.register(ep, Greeter { "g" }, plugin) }
        assertEquals(n, reg.extensions(ep).size)
    }
}
