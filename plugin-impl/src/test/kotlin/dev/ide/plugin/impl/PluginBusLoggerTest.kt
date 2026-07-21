package dev.ide.plugin.impl

import dev.ide.platform.PluginId
import dev.ide.platform.Topic
import dev.ide.platform.impl.ExtensionRegistryImpl
import dev.ide.platform.impl.MessageBusImpl
import dev.ide.platform.log.Log
import dev.ide.plugin.Plugin
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.PluginRegistration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/** A single-method listener + topic a test plugin subscribes to / publishes on. */
fun interface TestEventListener {
    fun onEvent(value: String)
}

private val TEST_TOPIC = Topic("test.plugin.events", TestEventListener::class.java)

/** The bus + logger surface added to [PluginRegistration]: a plugin can subscribe (tracked for unload) and
 *  publish on its own topic, and get a logger attributed to its id. */
class PluginBusLoggerTest {

    @Test
    fun `plugin subscribes via busConnection and stops receiving after unload`() {
        val received = CopyOnWriteArrayList<String>()
        val bus = MessageBusImpl()
        val mgr = PluginManager(ExtensionRegistryImpl(), bus)
        mgr.loadAll(listOf(object : Plugin {
            override val manifest = PluginManifest(id = "subscriber", name = "subscriber")
            override fun register(reg: PluginRegistration) {
                reg.busConnection().subscribe(TEST_TOPIC, TestEventListener { received.add(it) })
            }
        }))

        bus.syncPublisher(TEST_TOPIC).onEvent("one")
        assertEquals(listOf("one"), received)

        // Unload disposes the tracked connection, so its subscription is removed automatically.
        mgr.unload(PluginId("subscriber"))
        bus.syncPublisher(TEST_TOPIC).onEvent("two")
        assertEquals(listOf("one"), received)
    }

    @Test
    fun `plugin publishes on its own topic to another subscriber`() {
        val received = CopyOnWriteArrayList<String>()
        val bus = MessageBusImpl()
        // A host/other-plugin subscriber already listening.
        bus.connect().subscribe(TEST_TOPIC, TestEventListener { received.add(it) })

        PluginManager(ExtensionRegistryImpl(), bus).loadAll(listOf(object : Plugin {
            override val manifest = PluginManifest(id = "publisher", name = "publisher")
            override fun register(reg: PluginRegistration) {
                reg.messageBus.syncPublisher(TEST_TOPIC).onEvent("from-plugin")
            }
        }))

        assertEquals(listOf("from-plugin"), received)
    }

    @Test
    fun `plugin logger attributes its records to the plugin id`() {
        val marker = "plugin-logger-test-marker"
        PluginManager(ExtensionRegistryImpl(), MessageBusImpl()).loadAll(listOf(object : Plugin {
            override val manifest = PluginManifest(id = "logging-plugin", name = "logging-plugin")
            override fun register(reg: PluginRegistration) {
                reg.logger("work").info(marker)
            }
        }))

        val record = Log.recent().first { it.message == marker }
        assertEquals("logging-plugin", record.source)
        assertEquals("work", record.tag)
    }
}
