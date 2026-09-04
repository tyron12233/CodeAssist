package dev.ide.interp.impl

import dev.ide.interp.api.InterpretException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A real implementation of [iface] whose every call runs [invoke] in the interpreter.
 *
 * This is the member that makes a framework plugin possible. A framework expects to be handed an object whose
 * lifecycle it owns (`ApplicationListener`, `Screen`, `Runnable`), and an interpreted object is not an
 * instance of anything, so the only way across is a proxy that looks like the interface and routes each call
 * back in.
 *
 * The proxy is defined against **[iface]'s own loader**, so a plugin passing an interface from the framework
 * it bundles gets a proxy that framework can use. `equals`/`hashCode`/`toString` are answered here rather
 * than dispatched into the interpreter: a framework that puts the object in a map or logs it must not depend
 * on the user having written those.
 */
internal fun <T : Any> interpretedProxy(iface: Class<T>, invoke: (String, List<Any?>) -> Any?): T {
    if (!iface.isInterface) {
        throw InterpretException("${iface.name} is not an interface; only an interface can be proxied")
    }
    lateinit var proxy: Any
    val handler = InvocationHandler { _, method, args ->
        val values = args?.toList() ?: emptyList()
        if (method.isObjectMethod()) method.answerObjectMethod(proxy, values, invoke)
        else try {
            invoke(method.name, values)
        } catch (e: InterpretException) {
            // A default method the user's class does not override. Only "no such member" is worth handling
            // this way, and the interpreter's message is the one signal it gives; a genuine failure inside a
            // member that DOES exist has to propagate.
            if (method.isDefault && e.isMissingMember()) runDefault(proxy, method, args, e)
            else throw e
        }
    }
    proxy = Proxy.newProxyInstance(
        iface.classLoader ?: InterpretException::class.java.classLoader,
        arrayOf(iface),
        handler,
    )
    @Suppress("UNCHECKED_CAST")
    return proxy as T
}

/**
 * Run [method]'s own default body, so an interface with defaults is usable from an object that implements
 * only the abstract half. Self-calls inside the default body come back through the handler, so an override
 * still wins.
 *
 * `InvocationHandler.invokeDefault` is a JDK 16 API that **ART does not have** (the same reason
 * `AsmPeerFactory` realizes a default-inheriting peer as a generated subclass instead of a proxy). On device
 * there is nothing to fall back to, so the honest answer is the original "no such member" failure with the
 * platform's limitation named: the user's class has to override the member.
 */
private fun runDefault(proxy: Any, method: Method, args: Array<Any?>?, missing: InterpretException): Any? {
    if (!INVOKE_DEFAULT_AVAILABLE) {
        throw InterpretException(
            "`${method.name}` is a default method of ${method.declaringClass.simpleName} and this class does " +
                "not override it; on this platform an interpreted class must override every member it is " +
                "called through",
            missing,
        )
    }
    return InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))
}

/** Whether the JDK-16 default-method invoker exists here; false on ART. Probed once. */
private val INVOKE_DEFAULT_AVAILABLE: Boolean = runCatching {
    InvocationHandler::class.java.getMethod(
        "invokeDefault", Any::class.java, Method::class.java, Array<Any>::class.java,
    )
}.isSuccess

private fun Method.isObjectMethod(): Boolean = when (name) {
    "equals" -> parameterCount == 1
    "hashCode", "toString" -> parameterCount == 0
    else -> false
}

private fun Method.answerObjectMethod(
    proxy: Any,
    args: List<Any?>,
    invoke: (String, List<Any?>) -> Any?,
): Any = when (name) {
    "equals" -> proxy === args.firstOrNull()
    "hashCode" -> System.identityHashCode(proxy)
    // The interpreted object's own toString when it has one (a data class prints its fields), else a label
    // that says what this is rather than the proxy's default `$Proxy0@1a2b3c`.
    else -> runCatching { invoke("toString", emptyList())?.toString() }.getOrNull()
        ?: "interpreted ${proxy.javaClass.interfaces.firstOrNull()?.simpleName ?: "object"}"
}

/** Whether an [InterpretException] says the member is absent, as opposed to present and failing. */
private fun InterpretException.isMissingMember(): Boolean = message?.startsWith("no member") == true
