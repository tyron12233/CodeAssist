package dev.ide.jvm.kfixtures

import java.lang.reflect.Proxy

/** The Kotlin shape of faking an interface in a test: a property, a default method, a suspend member, and a
 *  trailing-lambda handler (see `ReflectProxy` for the Java cases). */
interface KRegistration {
    val pluginId: String
    fun register(id: String, order: Int = 0): Boolean
    fun label(): String = "plugin $pluginId"
    suspend fun load(): String
}

fun kotlinProxyFake(): String {
    val calls = mutableListOf<String>()
    val fake = Proxy.newProxyInstance(KRegistration::class.java.classLoader, arrayOf(KRegistration::class.java)) { _, method, args ->
        calls += method.name + "/" + (args?.size ?: 0)
        when (method.name) {
            "getPluginId" -> "dotnet"
            "register" -> (args!![0] as String).startsWith("d")
            "label" -> "fake label"
            else -> null
        }
    } as KRegistration
    return "${fake.pluginId},${fake.register("dll")},${fake.register("x", 2)},${fake.label()}|${calls.joinToString(";")}"
}
