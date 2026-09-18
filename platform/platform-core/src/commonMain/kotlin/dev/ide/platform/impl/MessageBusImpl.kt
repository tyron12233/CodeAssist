// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform.impl

import dev.ide.platform.ConcurrentMap
import dev.ide.platform.CopyOnWriteList
import dev.ide.platform.MessageBus
import dev.ide.platform.MessageBusConnection
import dev.ide.platform.Topic
import kotlin.concurrent.Volatile

/**
 * Synchronous [MessageBus]. [syncPublisher] hands back the topic's own fan-out over every currently-
 * subscribed listener; invoking a method on it reaches them all, in subscription order, on the calling
 * thread. Subscriptions belong to a [MessageBusConnection] and are removed when that connection is disposed.
 *
 * Listener isolation is the topic's to honour, and [dev.ide.platform.deliverToAll] is how: all listeners are
 * notified, then the first throwable is rethrown, so a faulty subscriber surfaces loudly without swallowing
 * the failure or costing the others their delivery.
 *
 * Topics are keyed by [Topic.name], so a producer and a consumer that each construct their own `Topic` with
 * the same name see the same channel.
 */
class MessageBusImpl : MessageBus {
    private val listeners = ConcurrentMap<String, CopyOnWriteList<Any>>()
    private val publishers = ConcurrentMap<String, Publisher>()

    /** A fan-out, and the exact listener snapshot it was built over. */
    private class Publisher(val over: List<Any>, val value: Any)

    override fun connect(): MessageBusConnection = ConnectionImpl()

    /**
     * A publisher is bound to the listeners it was built over, so it is cached against that exact snapshot
     * and rebuilt when the subscriptions change. [CopyOnWriteList.snapshot] returns the same instance until
     * something subscribes or unsubscribes, which makes the check an identity comparison.
     *
     * Worth the bookkeeping because a fan-out is not always cheap: the JVM's `Topic(name, Class)` spelling
     * builds a `java.lang.reflect.Proxy`, and publishing is on the model-commit and VFS-event paths.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <L : Any> syncPublisher(topic: Topic<L>): L {
        val over = listeners[topic.name]?.snapshot().orEmpty()
        publishers[topic.name]?.let { if (it.over === over) return it.value as L }
        val made = topic.fanOut(over as List<L>)
        publishers[topic.name] = Publisher(over, made)
        return made
    }

    private fun listenersFor(name: String): CopyOnWriteList<Any> =
        listeners.getOrPut(name) { CopyOnWriteList() }

    private inner class ConnectionImpl : MessageBusConnection {
        // (topicName, listener) pairs this connection added, so dispose() removes exactly them.
        private val mine = CopyOnWriteList<Pair<String, Any>>()

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
            for ((name, listener) in mine.snapshot()) {
                listeners[name]?.remove(listener)
            }
            mine.clear()
        }
    }
}
