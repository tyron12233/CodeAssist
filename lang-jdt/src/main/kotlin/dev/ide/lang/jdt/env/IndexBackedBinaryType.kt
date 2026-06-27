package dev.ide.lang.jdt.env

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader
import org.eclipse.jdt.internal.compiler.env.IBinaryAnnotation
import org.eclipse.jdt.internal.compiler.env.IBinaryField
import org.eclipse.jdt.internal.compiler.env.IBinaryMethod
import org.eclipse.jdt.internal.compiler.env.IBinaryNestedType
import org.eclipse.jdt.internal.compiler.env.IBinaryType
import org.eclipse.jdt.internal.compiler.env.IBinaryTypeAnnotation
import org.eclipse.jdt.internal.compiler.env.IRecordComponent
import org.eclipse.jdt.internal.compiler.env.ITypeAnnotationWalker
import org.eclipse.jdt.internal.compiler.impl.Constant
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI

/**
 * SPIKE (not yet wired into resolution): proof that ecj can build a type binding from an [IBinaryType] we
 * supply from the index instead of from parsed `.class` bytes. ecj's `findType` returns a
 * `NameEnvironmentAnswer` wrapping an [IBinaryType] (the interface `ClassFileReader` implements); ecj's
 * `BinaryTypeBinding` is built purely against that interface, so a faithful index-backed implementation lets
 * ecj keep doing all resolution / overloading / generics / diagnostics with no jar bytes at resolve time.
 *
 * The de-risking choice: [BinaryTypeCodec.encode] reads the REAL [ClassFileReader] output (we have the bytes
 * at index time) and serializes the structural surface ecj consumes; [BinaryTypeCodec.decode] reconstructs an
 * [IBinaryType] returning exactly those values. So `ClassFileReader` stays the source of truth and we cache
 * its result, rather than re-deriving the parse.
 *
 * Spike gaps (documented; close before production):
 *  - annotations / parameter annotations / type annotations are dropped (returns null). `@Deprecated` and the
 *    other standard annotations ecj folds into [IBinaryType.getTagBits] ARE preserved (tag bits are
 *    serialized), so deprecation diagnostics survive; custom/null-analysis annotations would not.
 *  - field compile-time constants are dropped ([Constant.NotAConstant]); constant-folding diagnostics (a
 *    library `static final` used in a `case`/constant expression) would differ.
 *  - records (components), sealed permitted-subtypes, JPMS module name, and argument names are dropped.
 */
internal object BinaryTypeCodec {

    private const val VERSION = 1

    fun encode(type: IBinaryType): ByteArray {
        val bos = ByteArrayOutputStream(1024)
        DataOutputStream(bos).use { o ->
            o.writeByte(VERSION)
            o.chars(type.name)
            o.chars(type.sourceName)
            o.chars(type.sourceFileName())
            o.chars(type.fileName)
            o.chars(type.enclosingTypeName)
            o.chars(type.superclassName)
            o.charArr(type.interfaceNames)
            o.chars(type.genericSignature)
            o.writeInt(type.modifiers)
            o.writeLong(type.tagBits)
            o.writeBoolean(type.isAnonymous)
            o.writeBoolean(type.isLocal)
            o.writeBoolean(type.isMember)

            val members = type.memberTypes ?: emptyArray()
            o.writeInt(members.size)
            for (m in members) { o.chars(m.name); o.chars(m.enclosingTypeName); o.writeInt(m.modifiers) }

            val fields = type.fields ?: emptyArray()
            o.writeInt(fields.size)
            for (f in fields) { o.chars(f.name); o.chars(f.typeName); o.chars(f.genericSignature); o.writeInt(f.modifiers); o.writeLong(f.tagBits) }

            val methods = type.methods ?: emptyArray()
            o.writeInt(methods.size)
            for (m in methods) {
                o.chars(m.selector); o.chars(m.methodDescriptor); o.chars(m.genericSignature)
                o.writeInt(m.modifiers); o.writeLong(m.tagBits)
                o.writeBoolean(m.isConstructor); o.writeBoolean(m.isClinit); o.writeInt(m.parameterCount)
                o.charArr(m.exceptionTypeNames)
            }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): IBinaryType {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readByte().toInt() == VERSION) { "unsupported binary-type codec version" }
            val name = i.chars(); val sourceName = i.chars(); val sourceFileName = i.chars()
            val fileName = i.chars(); val enclosingTypeName = i.chars(); val superclassName = i.chars()
            val interfaceNames = i.charArr(); val genericSignature = i.chars()
            val modifiers = i.readInt(); val tagBits = i.readLong()
            val isAnonymous = i.readBoolean(); val isLocal = i.readBoolean(); val isMember = i.readBoolean()

            val members = Array(i.readInt()) { NestedType(i.chars(), i.chars(), i.readInt()) }
            val fields = Array(i.readInt()) { Field(i.chars(), i.chars(), i.chars(), i.readInt(), i.readLong()) }
            val methods = Array(i.readInt()) {
                val sel = i.chars(); val desc = i.chars(); val gen = i.chars()
                val mod = i.readInt(); val tb = i.readLong()
                val ctor = i.readBoolean(); val clinit = i.readBoolean(); val pc = i.readInt()
                val exc = i.charArr()
                Method(sel, desc, gen, mod, tb, ctor, clinit, pc, exc)
            }
            return Type(name, sourceName, sourceFileName, fileName, enclosingTypeName, superclassName,
                interfaceNames, genericSignature, modifiers, tagBits, isAnonymous, isLocal, isMember,
                members.cast(), fields.cast(), methods.cast())
        }
    }

    // ---- IBinaryType over decoded data ----

    private class Type(
        private val name: CharArray?, private val sourceName: CharArray?, private val sourceFileName: CharArray?,
        private val fileName: CharArray?, private val enclosingTypeName: CharArray?, private val superclassName: CharArray?,
        private val interfaceNames: Array<CharArray>?, private val genericSignature: CharArray?,
        private val modifiers: Int, private val tagBits: Long,
        private val anonymous: Boolean, private val local: Boolean, private val member: Boolean,
        private val memberTypes: Array<IBinaryNestedType>, private val fields: Array<IBinaryField>, private val methods: Array<IBinaryMethod>,
    ) : IBinaryType {
        override fun getName() = name
        override fun getSourceName() = sourceName
        override fun sourceFileName() = sourceFileName
        override fun getFileName() = fileName
        override fun getEnclosingTypeName() = enclosingTypeName
        override fun getSuperclassName() = superclassName
        override fun getInterfaceNames() = interfaceNames
        override fun getGenericSignature() = genericSignature
        override fun getModifiers() = modifiers
        override fun getTagBits() = tagBits
        override fun isAnonymous() = anonymous
        override fun isLocal() = local
        override fun isMember() = member
        override fun isBinaryType() = true
        override fun isRecord() = false
        override fun getMemberTypes() = memberTypes
        override fun getFields() = fields
        override fun getMethods() = methods
        override fun getRecordComponents(): Array<IRecordComponent>? = null
        override fun getAnnotations(): Array<IBinaryAnnotation>? = null
        override fun getTypeAnnotations(): Array<IBinaryTypeAnnotation>? = null
        override fun getPermittedSubtypesNames(): Array<CharArray>? = null
        override fun getMissingTypeNames(): Array<Array<CharArray>>? = null
        override fun getModule(): CharArray? = null
        override fun getEnclosingMethod(): CharArray? = null
        override fun getURI(): URI? = null
        override fun enrichWithExternalAnnotationsFor(walker: ITypeAnnotationWalker?, member: Any?, environment: LookupEnvironment?): ITypeAnnotationWalker =
            walker ?: ITypeAnnotationWalker.EMPTY_ANNOTATION_WALKER
        override fun getExternalAnnotationStatus() = BinaryTypeBinding.ExternalAnnotationStatus.NOT_EEA_CONFIGURED
    }

    private class Method(
        private val selector: CharArray?, private val descriptor: CharArray?, private val genericSignature: CharArray?,
        private val modifiers: Int, private val tagBits: Long, private val ctor: Boolean, private val clinit: Boolean,
        private val parameterCount: Int, private val exceptionTypeNames: Array<CharArray>?,
    ) : IBinaryMethod {
        override fun getSelector() = selector
        override fun getMethodDescriptor() = descriptor
        override fun getGenericSignature() = genericSignature
        override fun getModifiers() = modifiers
        override fun getTagBits() = tagBits
        override fun isConstructor() = ctor
        override fun isClinit() = clinit
        override fun getParameterCount() = parameterCount
        override fun getExceptionTypeNames() = exceptionTypeNames
        override fun getArgumentNames(): Array<CharArray>? = null
        override fun getDefaultValue(): Any? = null
        override fun getAnnotations(): Array<IBinaryAnnotation>? = null
        override fun getParameterAnnotations(index: Int, classFileName: CharArray?): Array<IBinaryAnnotation>? = null
        override fun getAnnotatedParametersCount() = 0
        override fun getTypeAnnotations(): Array<IBinaryTypeAnnotation>? = null
    }

    private class Field(
        private val name: CharArray?, private val typeName: CharArray?, private val genericSignature: CharArray?,
        private val modifiers: Int, private val tagBits: Long,
    ) : IBinaryField {
        override fun getName() = name
        override fun getTypeName() = typeName
        override fun getGenericSignature() = genericSignature
        override fun getModifiers() = modifiers
        override fun getTagBits() = tagBits
        override fun getConstant(): Constant = Constant.NotAConstant
        override fun getAnnotations(): Array<IBinaryAnnotation>? = null
        override fun getTypeAnnotations(): Array<IBinaryTypeAnnotation>? = null
    }

    private class NestedType(
        private val name: CharArray?, private val enclosingTypeName: CharArray?, private val modifiers: Int,
    ) : IBinaryNestedType {
        override fun getName() = name
        override fun getEnclosingTypeName() = enclosingTypeName
        override fun getModifiers() = modifiers
    }

    // ---- char[] (de)serialization (presence-flagged, since many accessors are nullable) ----

    private fun DataOutputStream.chars(v: CharArray?) {
        if (v == null) writeBoolean(false) else { writeBoolean(true); writeUTF(String(v)) }
    }
    private fun DataInputStream.chars(): CharArray? = if (readBoolean()) readUTF().toCharArray() else null

    private fun DataOutputStream.charArr(v: Array<CharArray>?) {
        if (v == null) { writeInt(-1); return }
        writeInt(v.size); for (e in v) writeUTF(String(e))
    }
    private fun DataInputStream.charArr(): Array<CharArray>? {
        val n = readInt(); if (n < 0) return null
        return Array(n) { readUTF().toCharArray() }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> Array<*>.cast(): Array<T> = Array(size) { this[it] as T }
}
