package dev.ide.lang.kotlin.index

import dev.ide.kotlin.classfile.ClassFile

import dev.ide.index.AnnotatedExternalizer
import dev.ide.index.AnnotatedValue
import dev.ide.index.AnnotationIndex
import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringKeyDescriptor
import dev.ide.index.SubtypeExternalizer
import dev.ide.index.SubtypeIndex
import dev.ide.index.SubtypeValue

/**
 * The BINARY producers of the direct-inheritor ([SubtypeIndex.BINARY]) and annotated-by
 * ([AnnotationIndex.BINARY]) indexes — a header-only ASM read per classpath `.class` (no member decode:
 * that stays `kotlin.typeShape`'s job). Keys are SHORT names ([SubtypeIndex.key] / [AnnotationIndex.key])
 * so binary and resolution-free source entries share one query shape; a binary value carries the exact FQN
 * for precise filtering. `java.lang.Object` supertypes and the `@kotlin.Metadata` stamp are skipped —
 * every class carries them, so their buckets would be the whole classpath.
 */
private val binaryClassFilter = InputFilter {
    (it.origin == IndexOrigin.SDK || it.origin == IndexOrigin.LIBRARY) &&
        it.unitName?.endsWith(".class") == true &&
        !it.unitName!!.endsWith("module-info.class") && !it.unitName!!.endsWith("package-info.class")
}

private fun kindOf(access: Int): String = when {
    access and ClassFile.ACC_ANNOTATION != 0 -> "annotation"
    access and ClassFile.ACC_INTERFACE != 0 -> "interface"
    access and ClassFile.ACC_ENUM != 0 -> "enum"
    else -> "class"
}

private fun dotted(internalName: String) = internalName.replace('/', '.')

/** An annotation descriptor (`Landroidx/compose/runtime/Composable;`) to its dotted FQN, or null to skip. */
private fun annotationFqn(descriptor: String): String? {
    if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null
    val fqn = dotted(descriptor.substring(1, descriptor.length - 1))
    // Universal stamps: on (nearly) every Kotlin class/callable — a bucket of the whole classpath is noise.
    if (fqn == "kotlin.Metadata" || fqn == "kotlin.jvm.internal.SourceDebugExtension") return null
    return fqn
}

object BinarySubtypeIndex : IndexExtension<String, SubtypeValue> {
    override val id: IndexId = SubtypeIndex.BINARY
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = SubtypeExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = binaryClassFilter

    override fun index(input: IndexInput): Map<String, Collection<SubtypeValue>> {
        val classFile = sharedClassFile(input) ?: return emptyMap()
        if (classFile.accessFlags and ClassFile.ACC_SYNTHETIC != 0) return emptyMap()
        val fqn = dotted(classFile.thisClass)
        val kind = kindOf(classFile.accessFlags)
        val out = HashMap<String, MutableList<SubtypeValue>>()
        val supers = ArrayList<String>(1 + classFile.interfaces.size)
        classFile.superClass
            ?.takeIf { it != "java/lang/Object" && it != "java/lang/Record" && it != "java/lang/Enum" }
            ?.let { supers += dotted(it) }
        classFile.interfaces.forEach { supers += dotted(it) }
        for (s in supers) {
            if (s == "kotlin.jvm.internal.Lambda" || s.startsWith("kotlin.jvm.functions.Function")) continue
            out.getOrPut(SubtypeIndex.key(s)) { ArrayList() }.add(SubtypeValue(fqn, kind, s))
        }
        return out
    }
}

object BinaryAnnotationIndex : IndexExtension<String, AnnotatedValue> {
    override val id: IndexId = AnnotationIndex.BINARY
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = AnnotatedExternalizer
    override val matching = MatchingMode.PREFIX_ONLY
    override val inputFilter = binaryClassFilter

    override fun index(input: IndexInput): Map<String, Collection<AnnotatedValue>> {
        val classFile = sharedClassFile(input) ?: return emptyMap()
        val out = HashMap<String, MutableList<AnnotatedValue>>()
        val owner = dotted(classFile.thisClass)
        val ownerKind = kindOf(classFile.accessFlags)

        fun emit(declFqn: String, declKind: String, descriptor: String) {
            val ann = annotationFqn(descriptor) ?: return
            out.getOrPut(AnnotationIndex.key(ann)) { ArrayList() }.add(AnnotatedValue(declFqn, declKind, ann))
        }

        for (descriptor in classFile.annotations) emit(owner, ownerKind, descriptor)
        for (method in classFile.methods) {
            if (method.access and ClassFile.ACC_SYNTHETIC != 0) continue
            for (descriptor in method.annotations) emit("$owner#${method.name}", "method", descriptor)
        }
        for (field in classFile.fields) {
            if (field.access and ClassFile.ACC_SYNTHETIC != 0) continue
            for (descriptor in field.annotations) emit("$owner#${field.name}", "field", descriptor)
        }
        return out
    }
}
