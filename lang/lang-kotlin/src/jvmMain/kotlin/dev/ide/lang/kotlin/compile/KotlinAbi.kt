package dev.ide.lang.kotlin.compile

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.security.MessageDigest
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.isInline
import kotlin.metadata.jvm.KotlinClassMetadata

/**
 * The ABI snapshot of a compiled `.class`, used by [IncrementalKotlinCompiler]'s fast path: a hash that
 * changes iff a change to this class could force a dependent to recompile. When a recompiled class's
 * snapshot equals its previous one, dependents are provably unaffected, so only the edited file is rebuilt.
 *
 * For an ordinary class the snapshot covers the public surface only: the class header, its non-private
 * field/method signatures, and the `@kotlin.Metadata` blob (which carries Kotlin-level shape erased from the
 * bytecode: nullability, variance, default-argument presence, the declaration set, `internal` visibility).
 * Method bodies and private members are excluded, so editing a method body leaves the snapshot stable.
 *
 * A class that declares an inline function (or inline property accessor) is a special case: callers bake its
 * body into their own bytecode, so its body is part of its ABI. For those the whole class file is hashed
 * conservatively, so any change at all re-snapshots and forces a full module recompile.
 *
 * `.class` bytes are deterministic (no timestamps), so an unchanged class always re-snapshots identically.
 */
object KotlinAbi {

    /** A stable hex digest of [classBytes]'s ABI (see the class doc for what's included). */
    fun snapshot(classBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        if (hasInlineDeclarations(classBytes)) {
            // Inline bodies are part of the ABI → hash everything (any change re-snapshots).
            md.update(classBytes)
            return hex(md)
        }
        AbiReader(md).read(classBytes)
        return hex(md)
    }

    private fun hex(md: MessageDigest): String =
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 32)

    /** True when the class's `@kotlin.Metadata` declares any inline function or inline property accessor. */
    private fun hasInlineDeclarations(classBytes: ByteArray): Boolean {
        val annotation = extractMetadata(classBytes) ?: return false
        val md = runCatching { KotlinClassMetadata.readLenient(annotation) }.getOrNull() ?: return false
        val container: KmDeclarationContainer = when (md) {
            is KotlinClassMetadata.Class -> md.kmClass
            is KotlinClassMetadata.FileFacade -> md.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> md.kmPackage
            else -> return false
        }
        if (container.functions.any { it.isInline }) return true
        return container.properties.any { it.getter.isInline || it.setter?.isInline == true }
    }

    /** Read the raw `@kotlin.Metadata` annotation off [classBytes] (null when the class is plain Java). */
    private fun extractMetadata(classBytes: ByteArray): Metadata? {
        var found: MetadataBuilder? = null
        val cv = object : ClassVisitor(Opcodes.ASM9) {
            override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                if (descriptor != "Lkotlin/Metadata;") return null
                val b = MetadataBuilder(); found = b; return b.visitor()
            }
        }
        runCatching { ClassReader(classBytes).accept(cv, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
        return found?.build()
    }

    /** Accumulates the few `@kotlin.Metadata` members needed to reconstruct a [Metadata] instance. */
    private class MetadataBuilder {
        private var kind = 1
        private var mv = IntArray(0)
        private val d1 = ArrayList<String>()
        private val d2 = ArrayList<String>()
        private var extraInt = 0
        private var extraString = ""
        private var packageName = ""

        fun visitor(): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) {
                when (name) {
                    "k" -> kind = value as? Int ?: kind
                    "xi" -> extraInt = value as? Int ?: extraInt
                    "xs" -> extraString = value as? String ?: extraString
                    "pn" -> packageName = value as? String ?: packageName
                    "mv" -> if (value is IntArray) mv = value   // ASM delivers primitive arrays via visit()
                }
            }
            override fun visitArray(name: String?): AnnotationVisitor = object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(n: String?, value: Any?) {
                    when (name) {
                        "mv" -> if (value is Int) mv += value
                        "d1" -> if (value is String) d1.add(value)
                        "d2" -> if (value is String) d2.add(value)
                    }
                }
            }
        }

        fun build(): Metadata = Metadata(
            kind = kind, metadataVersion = mv, data1 = d1.toTypedArray(), data2 = d2.toTypedArray(),
            extraInt = extraInt, extraString = extraString, packageName = packageName,
        )
    }

    /** Digests the public-surface members of an ordinary (non-inline) class, ignoring bodies and privates. */
    private class AbiReader(private val md: MessageDigest) {
        fun read(classBytes: ByteArray) {
            val cv = object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    // Class version is excluded on purpose: a tooling/jvmTarget bump that only shifts the
                    // class-file version must not masquerade as an API change.
                    feed("C", access and ABI_CLASS_FLAGS, name, signature, superName, interfaces?.sorted()?.joinToString(","))
                }
                override fun visitField(access: Int, name: String?, descriptor: String?, signature: String?, value: Any?): FieldVisitor? {
                    if (access and Opcodes.ACC_PRIVATE == 0) feed("F", access, name, descriptor, signature, value?.toString())
                    return null
                }
                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    if (access and Opcodes.ACC_PRIVATE == 0) feed("M", access, name, descriptor, signature, exceptions?.sorted()?.joinToString(","))
                    return null   // no MethodVisitor → bodies are never inspected
                }
                override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
                    // Fold @kotlin.Metadata in: it encodes Kotlin shape (nullability, defaults, declaration set,
                    // `internal`) the erased JVM signatures above miss. Body-only edits leave it unchanged.
                    if (descriptor != "Lkotlin/Metadata;") return null
                    feed("A", 0, descriptor)
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        // Primitive arrays (e.g. the `mv` IntArray) arrive here, not via visitArray — render
                        // their *contents*, never Object.toString() (which is a nondeterministic identity hash).
                        override fun visit(name: String?, value: Any?) { feed("av", 0, name, render(value)) }
                        override fun visitArray(name: String?): AnnotationVisitor {
                            feed("ar", 0, name)
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(n: String?, value: Any?) { feed("ai", 0, render(value)) }
                            }
                        }
                    }
                }
            }
            runCatching { ClassReader(classBytes).accept(cv, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
        }

        private fun feed(tag: String, access: Int, vararg parts: String?) {
            md.update(tag.toByteArray(Charsets.UTF_8)); md.update(access.toByte())
            for (p in parts) { md.update(0); md.update((p ?: "").toByteArray(Charsets.UTF_8)) }
        }

        /** Deterministic rendering of an annotation value — flattening primitive arrays by content. */
        private fun render(v: Any?): String = when (v) {
            null -> ""
            is IntArray -> v.joinToString(",")
            is ByteArray -> v.joinToString(",")
            is BooleanArray -> v.joinToString(",")
            is CharArray -> v.joinToString(",")
            is ShortArray -> v.joinToString(",")
            is LongArray -> v.joinToString(",")
            is FloatArray -> v.joinToString(",")
            is DoubleArray -> v.joinToString(",")
            is Array<*> -> v.joinToString(",") { render(it) }
            else -> v.toString()
        }
    }

    /** Class-access bits that matter to the ABI (visibility/finality/kind); strips noise like ACC_SUPER. */
    private const val ABI_CLASS_FLAGS =
        Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL or
            Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE or Opcodes.ACC_ENUM or Opcodes.ACC_ANNOTATION or
            Opcodes.ACC_STATIC
}
