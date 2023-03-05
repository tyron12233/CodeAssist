package com.tyron.builder.gradle.internal.generators

import com.tyron.builder.api.variant.BuildConfigField
import com.squareup.javawriter.JavaWriter
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.IOException
import java.io.Serializable
import java.util.EnumSet
import javax.lang.model.element.Modifier

@Throws(IOException::class)
fun <T: Serializable> BuildConfigField<T>.emit(name: String, writer: ClassWriter) {
    val pfsOpcodes = Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC
    when (type) {
        "boolean" ->
            writer.visitField(pfsOpcodes, name, Type.getDescriptor(Boolean::class.java), null, value).visitEnd()
        "int" ->
            writer.visitField(pfsOpcodes, name, Type.getDescriptor(Int::class.java), null, value).visitEnd()
        "long" ->
            writer.visitField(pfsOpcodes, name, Type.getDescriptor(Long::class.java), null, value).visitEnd()
        "String" ->
            writer.visitField(pfsOpcodes, name, Type.getDescriptor(String::class.java), null, value.toString().removeSurrounding("\"")).visitEnd()
        else -> throw IllegalArgumentException(
            """BuildConfigField name: $name type: $type and value type: ${value.javaClass
                .name} cannot be emitted.""".trimMargin())
    }
}

@Throws(IOException::class)
fun <T: Serializable> BuildConfigField<T>.emit(name: String, writer: JavaWriter) {
    val publicStaticFinal = EnumSet.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
    if (comment != null) {
        writer.emitSingleLineComment(comment)
    }
    val valueAsString = value.toString()
    // Hack (see IDEA-100046): We want to avoid reporting "condition is always
    // true" from the data flow inspection, so use a non-constant value.
    // However, that defeats the purpose of this flag (when not in debug mode,
    // if (BuildConfig.DEBUG && ...) will be completely removed by
    // the compiler), so as a hack we do it only for the case where debug is
    // true, which is the most likely scenario while the user is looking
    // at source code. map.put(PH_DEBUG, Boolean.toString(mDebug));
    val emitValue = if (name == "DEBUG") {
        if (valueAsString == "true") "Boolean.parseBoolean(\"true\")" else "false"
    } else {
        when (type) {
            "long" -> if (valueAsString.toLongOrNull() == null) valueAsString else valueAsString.ensureSuffix('L')
            "float" -> if (valueAsString.toFloatOrNull() == null) valueAsString else valueAsString.ensureSuffix('f')
            else -> valueAsString
        }
    }

    writer.emitField(type, name, publicStaticFinal, emitValue)
}

private fun String.ensureSuffix(suffix: Char): String {
    return if (endsWith(suffix)) this else "$this$suffix"
}