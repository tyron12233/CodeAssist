package dev.ide.lang.kotlin.index

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
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

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
    access and Opcodes.ACC_ANNOTATION != 0 -> "annotation"
    access and Opcodes.ACC_INTERFACE != 0 -> "interface"
    access and Opcodes.ACC_ENUM != 0 -> "enum"
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
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return emptyMap()
        val r = runCatching { ClassReader(bytes) }.getOrNull() ?: return emptyMap()
        if (r.access and Opcodes.ACC_SYNTHETIC != 0) return emptyMap()
        val fqn = dotted(r.className)
        val kind = kindOf(r.access)
        val out = HashMap<String, MutableList<SubtypeValue>>()
        val supers = ArrayList<String>(1 + r.interfaces.size)
        r.superName?.takeIf { it != "java/lang/Object" && it != "java/lang/Record" && it != "java/lang/Enum" }
            ?.let { supers += dotted(it) }
        r.interfaces.forEach { supers += dotted(it) }
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
        val bytes = runCatching { input.bytes() }.getOrNull() ?: return emptyMap()
        val r = runCatching { ClassReader(bytes) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, MutableList<AnnotatedValue>>()
        val owner = dotted(r.className)
        val ownerKind = kindOf(r.access)

        fun emit(declFqn: String, declKind: String, descriptor: String) {
            val ann = annotationFqn(descriptor) ?: return
            out.getOrPut(AnnotationIndex.key(ann)) { ArrayList() }.add(AnnotatedValue(declFqn, declKind, ann))
        }

        r.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                emit(owner, ownerKind, descriptor); return null
            }

            override fun visitMethod(
                access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (access and Opcodes.ACC_SYNTHETIC != 0) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                        emit("$owner#$name", "method", desc); return null
                    }
                }
            }

            override fun visitField(
                access: Int, name: String, descriptor: String, signature: String?, value: Any?,
            ): FieldVisitor? {
                if (access and Opcodes.ACC_SYNTHETIC != 0) return null
                return object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(desc: String, visible: Boolean): AnnotationVisitor? {
                        emit("$owner#$name", "field", desc); return null
                    }
                }
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return out
    }
}
