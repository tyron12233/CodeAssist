package dev.ide.platform.impl

import dev.ide.platform.Disposable
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.PluginId
import dev.ide.platform.Topic
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration smoke test against platform-core alone: a plugin registers an extension and a
 * subscriber receives a published event.
 */
class PlatformCoreTest {

    fun interface ModuleType {
        fun id(): String
    }

    fun interface BuildListener {
        fun onBuilt(module: String)
    }

    @Test
    fun pluginRegistersExtensionAndReceivesEvents() {
        val platform = PlatformCore()
        try {
            // 1) a "plugin" contributes to an extension point
            val ep = ExtensionPoint<ModuleType>("platform.moduleType")
            platform.extensions.register(ep, ModuleType { "java-lib" }, PluginId("java-support"))
            assertEquals(listOf("java-lib"), platform.extensions.extensions(ep).map { it.id() })

            // 2) a listener subscribes and receives a published event
            val topic = Topic("platform.builds", BuildListener::class.java)
            val built = CopyOnWriteArrayList<String>()
            platform.messageBus.connect().subscribe(topic, BuildListener { built.add(it) })
            platform.messageBus.syncPublisher(topic).onBuilt("app")
            assertEquals(listOf("app"), built.toList())
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun disposeTearsDownRegisteredChildren() {
        val platform = PlatformCore()
        var disposed = false
        platform.register(Disposable { disposed = true })
        platform.dispose()
        assertTrue(disposed)
    }
}
