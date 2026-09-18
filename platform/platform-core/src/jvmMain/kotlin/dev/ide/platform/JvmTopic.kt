// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Build a [Topic] from the listener INTERFACE, the way topics were declared before they carried their own
 * fan-out. Kept so existing declarations and any plugin compiled against the old shape keep working.
 *
 * The fan-out is a dynamic proxy of [listenerType]: a call on it is reflected onto every listener. That is
 * what made this the JVM-only spelling — there is no `java.lang.reflect.Proxy` off the JVM — so common code,
 * and anything that has to run on iOS, names its fan-out directly instead:
 *
 * ```
 * Topic<VfsListener>("vfs.changes") { listeners -> VfsListener { e -> deliverToAll(listeners) { it.onEvents(e) } } }
 * ```
 *
 * Listener methods are expected to return `void`/`Unit`.
 */
@Suppress("FunctionName", "UNCHECKED_CAST")
fun <L : Any> Topic(name: String, listenerType: Class<L>): Topic<L> = Topic(name) { listeners ->
    val handler = InvocationHandler { proxy, method, args ->
        when (method.name) {
            "toString" -> if (args == null) return@InvocationHandler "Publisher($name)"
            "hashCode" -> if (args == null) return@InvocationHandler System.identityHashCode(proxy)
            "equals" -> if (args != null && args.size == 1) return@InvocationHandler proxy === args[0]
        }
        val callArgs = args ?: EMPTY_ARGS
        var firstError: Throwable? = null
        for (listener in listeners) {
            try {
                method.invoke(listener, *callArgs)
            } catch (e: InvocationTargetException) {
                if (firstError == null) firstError = e.targetException
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
            }
        }
        firstError?.let { throw it }
        null
    }
    listenerType.cast(
        Proxy.newProxyInstance(listenerType.classLoader, arrayOf<Class<*>>(listenerType), handler),
    ) as L
}

private val EMPTY_ARGS = emptyArray<Any?>()
