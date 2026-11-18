package dev.codeassist.csharp

import dev.ide.plugin.PluginRegistration
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * A [PluginRegistration] standing in for the host, for tests of what asks the host for directories and libraries.
 *
 * A dynamic proxy, not a class that implements the interface: the interface names a dozen platform types, and a
 * proxy answers the few members a test sets and returns an empty value for the rest, so the fake does not
 * break when the SPI gains a member.
 *
 * @param dataDir the answer to `dataDir`.
 * @param privateDataDir the answer to `privateDataDir`; null makes reading it throw `NoSuchMethodError`, as a
 * host older than SPI 3.4.0 does.
 * @param nativeLibraries the packaged libraries by plain name, as `nativeLibrary(name)` answers.
 */
internal class FakeHost(
    private val dataDir: Path,
    private val privateDataDir: Path? = null,
    private val nativeLibraries: Map<String, Path> = emptyMap(),
) {
    val registration: PluginRegistration = stub(PluginRegistration::class.java) { method, args ->
        when (method.name) {
            "getDataDir" -> dataDir
            "getPrivateDataDir" -> privateDataDir ?: throw NoSuchMethodError("getPrivateDataDir")
            "nativeLibrary" -> nativeLibraries[args?.firstOrNull() as? String]
            else -> emptyValue(method.returnType)
        }
    }

    private companion object {
        fun <T> stub(type: Class<T>, answer: (Method, Array<Any?>?) -> Any?): T =
        type.cast(
            Proxy.newProxyInstance(
                type.classLoader, 
                arrayOf(type)
            ) { _, method, args ->
                answer(method, args)
            },
        )

        /** What a member nobody set returns: zero for a primitive, an empty stub for an interface, else null. */
        fun emptyValue(type: Class<*>): Any? = when {
            type == Void.TYPE -> null
            type == java.lang.Boolean.TYPE -> false
            type == Integer.TYPE -> 0
            type == java.lang.Long.TYPE -> 0L
            type == java.lang.Double.TYPE -> 0.0
            type == java.lang.Float.TYPE -> 0f
            type == java.lang.Short.TYPE -> 0.toShort()
            type == java.lang.Byte.TYPE -> 0.toByte()
            type == Character.TYPE -> 0.toChar()
            type.isInterface -> stub(type) { method, _ -> emptyValue(method.returnType) }
            else -> null
        }
    }
}
