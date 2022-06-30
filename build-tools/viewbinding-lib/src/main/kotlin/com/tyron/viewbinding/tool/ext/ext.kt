package com.tyron.viewbinding.tool.ext

// import android.databinding.tool.LibTypes
import com.tyron.viewbinding.tool.expr.VersionProvider
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.ArrayList
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun String.stripNonJava() = this.split("[^a-zA-Z0-9]".toRegex()).map { it.trim() }.joinToCamelCaseAsVar()

/**
 * We keep track of these to be cleaned manually at the end of processing cycle.
 * This is really bad but these codes are from a day where javac would be re-created (hence safely
 * static). Now we need to clean them because javac is not re-created anymore between
 * compilations.
 *
 * Eventually, we should move to a better model similar to the UserProperty stuff in IJ
 * source.
 */

private val mappingHashes = CopyOnWriteArrayList<MutableMap<*, *>>()

fun cleanLazyProps() {
    mappingHashes.forEach {
        it.clear()
    }
}

private class LazyExt<K, T>(private val initializer: (k: K) -> T) : ReadOnlyProperty<K, T> {
    private val mapping = hashMapOf<K, T>()
    init {
        mappingHashes.add(mapping)
    }
    override fun getValue(thisRef: K, property: kotlin.reflect.KProperty<*>): T {
        val t = mapping[thisRef]
        if (t != null) {
            return t
        }
        val result = initializer(thisRef)
        mapping.put(thisRef, result)
        return result
    }
}

private class VersionedLazyExt<K, T>(private val initializer: (k: K) -> T) : ReadOnlyProperty<K, T> {
    private val mapping = hashMapOf<K, VersionedResult<T>>()
    init {
        mappingHashes.add(mapping)
    }
    override fun getValue(thisRef: K, property: KProperty<*>): T {
        val t = mapping[thisRef]
        val version = if (thisRef is VersionProvider) thisRef.version else 1
        if (t != null && version == t.version) {
            return t.result
        }
        val result = initializer(thisRef)
        mapping.put(thisRef, VersionedResult(version, result))
        return result
    }
}

data class VersionedResult<T>(val version: Int, val result: T)

fun <K, T> lazyProp(initializer: (k: K) -> T): ReadOnlyProperty<K, T> = LazyExt(initializer)
fun <K, T> versionedLazy(initializer: (k: K) -> T): ReadOnlyProperty<K, T> = VersionedLazyExt(initializer)

fun Class<*>.toJavaCode(): String {
    if (name.startsWith('[')) {
        val numArray = name.lastIndexOf('[') + 1
        val componentType: String
        when (name[numArray]) {
            'Z' -> componentType = "boolean"
            'B' -> componentType = "byte"
            'C' -> componentType = "char"
            'L' -> componentType = name.substring(numArray + 1, name.length - 1).replace('$', '.')
            'D' -> componentType = "double"
            'F' -> componentType = "float"
            'I' -> componentType = "int"
            'J' -> componentType = "long"
            'S' -> componentType = "short"
            else -> componentType = name.substring(numArray)
        }
        val arrayComp = name.substring(0, numArray).replace("[", "[]")
        return componentType + arrayComp
    } else {
        return name.replace("$", "")
    }
}

fun String.parseXmlResourceReference(): XmlResourceReference {
    require(startsWith('@')) { "Reference must start with '@': $this" }

    val creating = length > 1 && this[1] == '+'
    val start = if (creating) 2 else 1
    val colon = indexOf(':', start)
    val slash = indexOf('/', start)

    val type: String
    val namespace: String?
    val name: String
    when {
        // @id/foo, @+id/foo
        colon == -1 && slash != -1 -> {
            type = substring(start, slash)
            namespace = null
            name = substring(slash + 1)
        }
        // @android:id/foo, @+android:id/foo
        colon != -1 && slash != -1 && colon < slash -> {
            type = substring(colon + 1, slash)
            namespace = substring(start, colon)
            name = substring(slash + 1)
        }
        // @id/android:foo, @+id/android:foo
        colon != -1 && slash != -1 && slash < colon -> {
            type = substring(start, slash)
            namespace = substring(slash + 1, colon)
            name = substring(colon + 1)
        }
        else -> throw IllegalArgumentException("Invalid resource format: $this")
    }
    require(namespace == null || namespace.isNotEmpty()) { "Namespace cannot be empty: $this" }
    require(name.isNotEmpty()) { "Name cannot be empty: $this" }
    require(type.isNotEmpty()) { "Type cannot be empty: $this" }

    return XmlResourceReference(namespace, type, name.replace('.', '_'), creating)
}

data class XmlResourceReference(
    val namespace: String?,
    val type: String,
    val name: String,
    val creating: Boolean
)

fun String.toCamelCase(): String {
    val split = this.split("_")
    if (split.size == 0) return ""
    if (split.size == 1) return split[0].capitalizeUS()
    return split.joinToCamelCase()
}

fun String.toCamelCaseAsVar(): String {
    val split = this.split("_")
    if (split.isEmpty()) return ""
    if (split.size == 1) return split[0]
    return split.joinToCamelCaseAsVar()
}

// TODO replace with String.capitalize(Locale) from standard library once it is not experimental.
fun String.capitalizeUS() = if (isEmpty()) {
    ""
} else {
    substring(0, 1).toUpperCase(Locale.US) + substring(1)
}

// TODO replace with String.decapitalize(Locale) from standard library once it is not experimental.
fun String.decapitalizeUS() = if (isEmpty()) {
    ""
} else {
    substring(0, 1).toLowerCase(Locale.US) + substring(1)
}

fun String.br(): String =
    "BR.${if (this == "") "_all" else this}"

fun String.readableName() = stripNonJava()
/*
fun String.toTypeName(libTypes: LibTypes, imports: Map<String, String>) : TypeName {
    return this.toTypeName(
        libTypes = libTypes,
        imports = imports,
        useReplacements = true)
}

fun String.toTypeName(libTypes : LibTypes) : TypeName {
    return toTypeName(libTypes = libTypes, imports = null, useReplacements = false)
}*/

// tmp method for studio compatibility
fun String.toTypeName(useAndroidX: Boolean) : TypeName {
    return toTypeName(useAndroidX = useAndroidX, imports = null, useReplacements = false)
}

/*private fun String.toTypeName(
    libTypes: LibTypes,
    imports: Map<String, String>?,
    useReplacements: Boolean) : TypeName {
    return this.toTypeName(
        useAndroidX = libTypes.useAndroidX,
        imports = imports,
        useReplacements = useReplacements
    )
}*/
private fun String.toTypeName(
    useAndroidX: Boolean,
    imports: Map<String, String>?,
    useReplacements: Boolean) : TypeName {
    if (this.endsWith("[]")) {
        val qType = this.substring(0, this.length - 2).trim().toTypeName(
            useAndroidX = useAndroidX,
            imports = imports,
            useReplacements = useReplacements)
        return ArrayTypeName.of(qType)
    }
    val genericEnd = this.lastIndexOf(">")
    if (genericEnd >= 0) {
        val genericStart = this.indexOf("<")
        if (genericStart >= 0) {
            val typeParams = this.substring(genericStart + 1, genericEnd).trim()
            val typeParamsQualified = splitTemplateParameters(typeParams).map {
                it.toTypeName(
                    useAndroidX = useAndroidX,
                    imports = imports,
                    useReplacements = useReplacements)
            }
            val klass = this.substring(0, genericStart)
                .trim()
                .toTypeName(
                    useAndroidX = useAndroidX,
                    imports = imports,
                    useReplacements = useReplacements)
            return ParameterizedTypeName.get(klass as ClassName,
                *typeParamsQualified.toTypedArray())
        }
    }
    if (useReplacements) {
        // check for replacements
        val replacement = REPLACEMENTS[this]
        if (replacement != null) {
            return when(useAndroidX) {
                true -> replacement.androidX
                false -> replacement.support
            }
        }
    }
    val import = imports?.get(this)
    if (import != null) {
        return ClassName.bestGuess(import)
    }
    return PRIMITIVE_TYPE_NAME_MAP[this] ?: ClassName.bestGuess(this)
}

private fun splitTemplateParameters(templateParameters: String): ArrayList<String> {
    val list = ArrayList<String>()
    var index = 0
    var openCount = 0
    val arg = StringBuilder()
    while (index < templateParameters.length) {
        val c = templateParameters[index]
        if (c == ',' && openCount == 0) {
            list.add(arg.toString())
            arg.delete(0, arg.length)
        } else if (!Character.isWhitespace(c)) {
            arg.append(c)
            if (c == '<') {
                openCount++
            } else if (c == '>') {
                openCount--
            }
        }
        index++
    }
    list.add(arg.toString())
    return list
}

private val REPLACEMENTS = mapOf(
    "android.view.ViewStub" to Replacement(
        support = ClassName.get("android.databinding", "ViewStubProxy"),
        androidX = ClassName.get("androidx.databinding","ViewStubProxy"))
)

private val PRIMITIVE_TYPE_NAME_MAP = mapOf(
    TypeName.VOID.toString() to TypeName.VOID,
    TypeName.BOOLEAN.toString() to TypeName.BOOLEAN,
    TypeName.BYTE.toString() to TypeName.BYTE,
    TypeName.SHORT.toString() to TypeName.SHORT,
    TypeName.INT.toString() to TypeName.INT,
    TypeName.LONG.toString() to TypeName.LONG,
    TypeName.CHAR.toString() to TypeName.CHAR,
    TypeName.FLOAT.toString() to TypeName.FLOAT,
    TypeName.DOUBLE.toString() to TypeName.DOUBLE)

private data class Replacement(val support : ClassName, val androidX : ClassName)