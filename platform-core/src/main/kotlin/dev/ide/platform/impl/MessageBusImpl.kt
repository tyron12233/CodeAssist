package dev.ide.platform.impl

import dev.ide.platform.MessageBus
import dev.ide.platform.MessageBusConnection
import dev.ide.platform.Topic
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Synchronous [MessageBus]. [syncPublisher] hands back a dynamic proxy of the listener interface;
 * invoking a method on it fans out to every currently-subscribed listener of that topic, in
 * subscription order, on the calling thread. Subscriptions belong to a [MessageBusConnection] and are
 * removed when that connection is disposed.
 *
 * Listener isolation: one listener throwing does not stop delivery to the rest. All listeners are
 * notified, then the first throwable is rethrown — so a faulty subscriber surfaces loudly without
 * silently swallowing it or dropping delivery to the others.
 *
 * Topics are keyed by [Topic.name]; listener methods are expected to return `void`/`Unit`.
 */
class MessageBusImpl : MessageBus {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Any>>()
    private val publishers = ConcurrentHashMap<String, Any>()

    override fun connect(): MessageBusConnection = ConnectionImpl()

    @Suppress("UNCHECKED_CAST")
    override fun <L : Any> syncPublisher(topic: Topic<L>): L =
        publishers.getOrPut(topic.name) { createPublisher(topic) } as L

    private fun listenersFor(name: String): CopyOnWriteArrayList<Any> =
        listeners.getOrPut(name) { CopyOnWriteArrayList() }

    private fun <L : Any> createPublisher(topic: Topic<L>): L {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "toString" -> if (args == null) return@InvocationHandler "Publisher(${topic.name})"
                "hashCode" -> if (args == null) return@InvocationHandler System.identityHashCode(proxy)
                "equals" -> if (args != null && args.size == 1) return@InvocationHandler proxy === args[0]
            }
            val current = listeners[topic.name]
            var firstError: Throwable? = null
            if (current != null) {
                val callArgs = args ?: EMPTY
                for (listener in current) {
                    try {
                        method.invoke(listener, *callArgs)
                    } catch (e: InvocationTargetException) {
                        if (firstError == null) firstError = e.targetException
                    } catch (t: Throwable) {
                        if (firstError == null) firstError = t
                    }
                }
            }
            firstError?.let { throw it }
            null
        }
        val proxy = Proxy.newProxyInstance(
            topic.listenerType.classLoader,
            arrayOf<Class<*>>(topic.listenerType),
            handler,
        )
        return topic.listenerType.cast(proxy)
    }

    private inner class ConnectionImpl : MessageBusConnection {
        // (topicName, listener) pairs this connection added, so dispose() removes exactly them.
        private val mine = CopyOnWriteArrayList<Pair<String, Any>>()

        @Volatile
        private var disposed = false

        override fun <L : Any> subscribe(topic: Topic<L>, listener: L) {
            check(!disposed) { "Cannot subscribe on a disposed connection" }
            listenersFor(topic.name).add(listener)
            mine.add(topic.name to listener)
        }

        override fun dispose() {
            if (disposed) return
            disposed = true
            for ((name, listener) in mine) {
                listeners[name]?.remove(listener)
            }
            mine.clear()
        }
    }

    private companion object {
        val EMPTY = emptyArray<Any?>()
    }
}
