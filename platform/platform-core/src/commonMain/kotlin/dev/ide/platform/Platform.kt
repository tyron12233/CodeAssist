// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform


/**
 * platform-core — the substrate every other module sits on.
 *
 * No domain knowledge lives here: no notion of "project", "Android", "Java", or "Gradle".
 * It provides extensibility (extension points), eventing (message bus), lifecycle
 * (disposers), background work (activities), progress/cancellation, and the value types
 * shared across modules ([ContentHash], ids).
 */

/**
 * The message bus. The rest of what was here is in `dev.ide.platform` in `:model-api`, same package: plugin
 * identity, disposal, the extension framework, service keys and progress reporting.
 */

// ---------------------------------------------------------------------------
// Message bus
// ---------------------------------------------------------------------------

/**
 * A typed broadcast channel. Listener interface [L] defines the callbacks publishers invoke.
 *
 * A topic carries its own [fanOut]: given every subscribed listener, it returns ONE [L] that forwards to all
 * of them. That function is what [MessageBus.syncPublisher] hands back, and writing it is a one-liner at the
 * declaration, where the listener interface is statically known:
 *
 * ```
 * val CHANGES: Topic<VfsListener> = Topic("vfs.changes") { listeners ->
 *     VfsListener { events -> deliverToAll(listeners) { it.onEvents(events) } }
 * }
 * ```
 *
 * It used to be a `java.lang.Class` and the publisher a `java.lang.reflect.Proxy` over it, which is the
 * obvious way to turn "call any method on the publisher" into "call it on each listener" — and the reason
 * this type, and everything hanging off it, could not leave the JVM. There is no `Proxy` on Kotlin/Native.
 * Naming the fan-out costs one line per topic, removes reflection from the delivery path entirely, and is
 * checked by the compiler rather than at the first publish.
 *
 * The JVM keeps a `Topic(name, Class)` factory that builds the proxy fan-out, so existing declarations and
 * any plugin compiled against the old shape still work; it is JVM-only by nature, and new topics should
 * prefer this constructor.
 */
class Topic<L : Any>(val name: String, val fanOut: (List<L>) -> L)

/**
 * Invoke [call] on every listener, then rethrow the first failure.
 *
 * The delivery contract a [Topic.fanOut] is expected to honour, factored out so each one is a single line.
 * Isolation is the point: one listener throwing must not stop delivery to the rest, and must not be
 * swallowed either, so every listener is notified and the first throwable surfaces afterwards.
 */
inline fun <L> deliverToAll(listeners: List<L>, call: (L) -> Unit) {
    var first: Throwable? = null
    for (listener in listeners) {
        try {
            call(listener)
        } catch (t: Throwable) {
            if (first == null) first = t
        }
    }
    first?.let { throw it }
}

interface MessageBus {
    fun connect(): MessageBusConnection

    /** A publisher for [topic]: calling a method on it invokes that method on every subscribed listener. */
    fun <L : Any> syncPublisher(topic: Topic<L>): L
}

interface MessageBusConnection : Disposable {
    fun <L : Any> subscribe(topic: Topic<L>, listener: L)
}
