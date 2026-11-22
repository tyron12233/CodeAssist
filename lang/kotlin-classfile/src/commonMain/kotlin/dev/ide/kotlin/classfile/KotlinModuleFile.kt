package dev.ide.kotlin.classfile

/**
 * Reads the OPTIONAL ANNOTATION CLASSES out of a library's `META-INF/<module>.kotlin_module`.
 *
 * An `@OptionalExpectation expect annotation class` (`kotlin.js.JsName`, `kotlin.native.concurrent.ThreadLocal`,
 * `kotlin.jvm.JvmName`'s multiplatform siblings) has no `actual` on the JVM, so no class file carries it. The
 * compiler still resolves it there, from the copy the module file keeps (`JvmModuleProtoBuf.Module`, field
 * `optional_annotation_class`), which uses the module's own string and qualified-name tables.
 *
 * The payload starts with the metadata version (a big-endian count, then that many ints) and, from 1.4 on, a
 * big-endian flags int; the protobuf follows.
 */
object KotlinModuleFile {

    // jvm_module.proto, `message Module`
    private const val MODULE_STRING_TABLE = 4
    private const val MODULE_QUALIFIED_NAME_TABLE = 5
    private const val MODULE_OPTIONAL_ANNOTATION_CLASS = 16

    /** The optional annotation classes [bytes] declares, keyed by Kotlin FQN; empty when it is not a module file. */
    fun optionalAnnotationClasses(bytes: ByteArray): Map<String, KotlinClassInfo> {
        val start = protobufStart(bytes) ?: return emptyMap()
        var strings: List<String> = emptyList()
        var qualifiedNames: List<BuiltInsNameResolver.QualifiedName> = emptyList()
        var any = false
        runCatching {
            val tables = ProtoReader(bytes, start)
            tables.forEachField { number, _ ->
                when (number) {
                    MODULE_STRING_TABLE -> { strings = BuiltInsNameResolver.readStrings(tables.readBytes()); true }
                    MODULE_QUALIFIED_NAME_TABLE -> { qualifiedNames = BuiltInsNameResolver.readQualifiedNames(tables.readBytes()); true }
                    MODULE_OPTIONAL_ANNOTATION_CLASS -> { any = true; false }
                    else -> false
                }
            }
        }.getOrElse { return emptyMap() }
        if (!any) return emptyMap()
        val names = BuiltInsNameResolver(strings, qualifiedNames)
        val out = LinkedHashMap<String, KotlinClassInfo>()
        runCatching {
            val reader = ProtoReader(bytes, start)
            reader.forEachField { number, _ ->
                if (number != MODULE_OPTIONAL_ANNOTATION_CLASS) return@forEachField false
                val info = KotlinMetadata.readClass(reader.readBytes(), names)
                info.name?.let { out[it] = info }
                true
            }
        }.getOrElse { return emptyMap() }
        return out
    }

    /** Where the protobuf begins: past the version ints and, for a 1.4+ version, the flags int. */
    private fun protobufStart(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        val count = readInt(bytes, 0)
        if (count < 0 || count > 16) return null
        var at = 4 + count * 4
        if (at > bytes.size) return null
        val major = if (count > 0) readInt(bytes, 4) else 0
        val minor = if (count > 1) readInt(bytes, 8) else 0
        if (major > 1 || (major == 1 && minor >= 4)) at += 4
        return if (at <= bytes.size) at else null
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
}
