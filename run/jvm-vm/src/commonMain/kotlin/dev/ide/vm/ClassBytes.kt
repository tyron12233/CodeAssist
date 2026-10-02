package dev.ide.vm

/**
 * A class file's constant pool, decoded into flat arrays indexed by pool slot.
 *
 * Every entry keeps its tag in [tags]. The payload depends on the tag: an `Integer` or `Float` keeps its bits
 * in [a]; a `Long` or `Double` keeps its bits in [wide]; a `Utf8` keeps its text in [utf8]; every reference
 * kind keeps its two indexes in [a] and [b] (`Class` and `String` and `MethodType` use only [a]).
 */
internal class ConstantPool(
    val tags: ByteArray,
    val a: IntArray,
    val b: IntArray,
    val wide: LongArray,
    val utf8: Array<String?>,
) {
    val size: Int get() = tags.size

    fun utf8(index: Int): String = utf8[index] ?: error("constant $index is not Utf8")
    fun className(index: Int): String = utf8(a[index])
    fun string(index: Int): String = utf8(a[index])
    fun memberOwner(index: Int): String = className(a[index])
    fun memberName(index: Int): String = utf8(a[b[index]])
    fun memberDescriptor(index: Int): String = utf8(b[b[index]])
    fun nameAndTypeName(index: Int): String = utf8(a[index])
    fun nameAndTypeDescriptor(index: Int): String = utf8(b[index])

    companion object {
        const val UTF8 = 1
        const val INTEGER = 3
        const val FLOAT = 4
        const val LONG = 5
        const val DOUBLE = 6
        const val CLASS = 7
        const val STRING = 8
        const val FIELDREF = 9
        const val METHODREF = 10
        const val INTERFACE_METHODREF = 11
        const val NAME_AND_TYPE = 12
        const val METHOD_HANDLE = 15
        const val METHOD_TYPE = 16
        const val DYNAMIC = 17
        const val INVOKE_DYNAMIC = 18
        const val MODULE = 19
        const val PACKAGE = 20
    }
}

/** One `exception_table` row: the handler at [handlerPc] covers `[startPc, endPc)`; [catchType] 0 means any. */
internal class ExceptionHandler(val startPc: Int, val endPc: Int, val handlerPc: Int, val catchType: Int)

internal class ParsedField(val access: Int, val name: String, val descriptor: String, val constantValue: Int, val signature: String? = null)

internal class ParsedMethod(
    val access: Int,
    val name: String,
    val descriptor: String,
    val code: ByteArray?,
    val maxStack: Int,
    val maxLocals: Int,
    val handlers: Array<ExceptionHandler>,
    /** `LineNumberTable` as (startPc, line) pairs, flattened; empty when the class was compiled without it. */
    val lines: IntArray,
    /** The generic `Signature`, when the method has type parameters or generic parameter types. */
    val signature: String? = null,
)

/** A `BootstrapMethods` row: the method handle constant and its static argument constants. */
internal class BootstrapMethod(val handle: Int, val arguments: IntArray)

/**
 * A class file read completely, method bodies included: what the VM links and runs.
 *
 * `:kotlin-classfile` reads the same format for the index and skips `Code` on purpose, since an index never
 * looks inside a method. An interpreter is the opposite case, so this is its own small reader. Only the
 * attributes execution needs are decoded (`Code`, `ConstantValue`, `BootstrapMethods`, `LineNumberTable`,
 * `SourceFile`); the rest are skipped by length.
 */
internal class ParsedClass(
    val access: Int,
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val pool: ConstantPool,
    val fields: List<ParsedField>,
    val methods: List<ParsedMethod>,
    val bootstrapMethods: List<BootstrapMethod>,
    val sourceFile: String?,
    /** This class's own `InnerClasses` row: its simple name (null when anonymous) and its outer class. */
    val innerName: String?,
    val outerName: String?,
    val isNested: Boolean,
    /** `EnclosingMethod`: present for local and anonymous classes. */
    val enclosingClass: String?,
    /** The class's generic `Signature` (type parameters, generic supertypes), when it has one. */
    val signature: String? = null,
) {
    companion object {
        fun read(bytes: ByteArray): ParsedClass = ClassBytesReader(bytes).read()
    }
}

private class ClassBytesReader(private val bytes: ByteArray) {
    private var at = 0

    private fun u1(): Int = bytes[at++].toInt() and 0xFF
    private fun u2(): Int = (u1() shl 8) or u1()
    private fun u4(): Int = (u2() shl 16) or u2()

    fun read(): ParsedClass {
        if (u4() != 0xCAFEBABE.toInt()) throw IllegalArgumentException("not a class file")
        u2(); u2() // minor, major
        val pool = readPool()
        val access = u2()
        val name = pool.className(u2())
        val superIndex = u2()
        val superName = if (superIndex == 0) null else pool.className(superIndex)
        val interfaces = List(u2()) { pool.className(u2()) }
        val fields = List(u2()) { readField(pool) }
        val methods = List(u2()) { readMethod(pool) }
        var bootstraps: List<BootstrapMethod> = emptyList()
        var sourceFile: String? = null
        var innerName: String? = null
        var outerName: String? = null
        var nested = false
        var enclosing: String? = null
        var classSignature: String? = null
        repeat(u2()) {
            val attr = pool.utf8(u2())
            val length = u4()
            val end = at + length
            when (attr) {
                "BootstrapMethods" -> bootstraps = List(u2()) {
                    val handle = u2()
                    BootstrapMethod(handle, IntArray(u2()) { u2() })
                }
                "SourceFile" -> sourceFile = pool.utf8(u2())
                "InnerClasses" -> repeat(u2()) {
                    val inner = u2(); val outer = u2(); val simple = u2(); u2()
                    if (inner != 0 && pool.className(inner) == name) {
                        nested = true
                        outerName = if (outer == 0) null else pool.className(outer)
                        innerName = if (simple == 0) null else pool.utf8(simple)
                    }
                }
                "EnclosingMethod" -> enclosing = pool.className(u2())
                "Signature" -> classSignature = pool.utf8(u2())
            }
            at = end
        }
        return ParsedClass(access, name, superName, interfaces, pool, fields, methods, bootstraps, sourceFile, innerName, outerName, nested, enclosing, classSignature)
    }

    private fun readPool(): ConstantPool {
        val count = u2()
        val tags = ByteArray(count)
        val a = IntArray(count)
        val b = IntArray(count)
        val wide = LongArray(count)
        val utf8 = arrayOfNulls<String>(count)
        var i = 1
        while (i < count) {
            val tag = u1()
            tags[i] = tag.toByte()
            when (tag) {
                ConstantPool.UTF8 -> {
                    val length = u2()
                    utf8[i] = decodeModifiedUtf8(bytes, at, length)
                    at += length
                }
                ConstantPool.INTEGER, ConstantPool.FLOAT -> a[i] = u4()
                ConstantPool.LONG, ConstantPool.DOUBLE -> {
                    val high = u4().toLong()
                    val low = u4().toLong() and 0xFFFFFFFFL
                    wide[i] = (high shl 32) or low
                    i++ // an 8-byte constant takes two pool slots
                }
                ConstantPool.CLASS, ConstantPool.STRING, ConstantPool.METHOD_TYPE,
                ConstantPool.MODULE, ConstantPool.PACKAGE -> a[i] = u2()
                ConstantPool.FIELDREF, ConstantPool.METHODREF, ConstantPool.INTERFACE_METHODREF,
                ConstantPool.NAME_AND_TYPE, ConstantPool.DYNAMIC, ConstantPool.INVOKE_DYNAMIC -> {
                    a[i] = u2(); b[i] = u2()
                }
                ConstantPool.METHOD_HANDLE -> {
                    a[i] = u1(); b[i] = u2()
                }
                else -> throw IllegalArgumentException("constant pool tag $tag at $i")
            }
            i++
        }
        return ConstantPool(tags, a, b, wide, utf8)
    }

    private fun readField(pool: ConstantPool): ParsedField {
        val access = u2()
        val name = pool.utf8(u2())
        val descriptor = pool.utf8(u2())
        var constant = 0
        var signature: String? = null
        repeat(u2()) {
            val attr = pool.utf8(u2())
            val length = u4()
            val end = at + length
            if (attr == "ConstantValue") constant = u2()
            if (attr == "Signature") signature = pool.utf8(u2())
            at = end
        }
        return ParsedField(access, name, descriptor, constant, signature)
    }

    private fun readMethod(pool: ConstantPool): ParsedMethod {
        val access = u2()
        val name = pool.utf8(u2())
        val descriptor = pool.utf8(u2())
        var code: ByteArray? = null
        var maxStack = 0
        var maxLocals = 0
        var handlers: Array<ExceptionHandler> = NO_HANDLERS
        var lines = NO_LINES
        var signature: String? = null
        repeat(u2()) {
            val attr = pool.utf8(u2())
            val length = u4()
            val end = at + length
            if (attr == "Signature") signature = pool.utf8(u2())
            if (attr == "Code") {
                maxStack = u2()
                maxLocals = u2()
                val codeLength = u4()
                code = bytes.copyOfRange(at, at + codeLength)
                at += codeLength
                handlers = Array(u2()) { ExceptionHandler(u2(), u2(), u2(), u2()) }
                repeat(u2()) {
                    val inner = pool.utf8(u2())
                    val innerLength = u4()
                    val innerEnd = at + innerLength
                    if (inner == "LineNumberTable") {
                        val n = u2()
                        val table = IntArray(n * 2)
                        for (k in 0 until n) { table[k * 2] = u2(); table[k * 2 + 1] = u2() }
                        lines = if (lines.isEmpty()) table else lines + table
                    }
                    at = innerEnd
                }
            }
            at = end
        }
        return ParsedMethod(access, name, descriptor, code, maxStack, maxLocals, handlers, lines, signature)
    }

    private companion object {
        val NO_HANDLERS = emptyArray<ExceptionHandler>()
        val NO_LINES = IntArray(0)
    }
}

/**
 * Decodes the class file's "modified UTF-8": NUL is two bytes and supplementary characters are surrogate
 * pairs encoded separately, so a plain UTF-8 decoder would get both wrong.
 */
internal fun decodeModifiedUtf8(bytes: ByteArray, offset: Int, length: Int): String {
    // Fast path: pure ASCII is by far the common case for names and descriptors.
    var ascii = true
    for (i in offset until offset + length) if (bytes[i] < 0 || bytes[i].toInt() == 0) { ascii = false; break }
    if (ascii) return bytes.decodeToString(offset, offset + length)
    val out = CharArray(length)
    var n = 0
    var i = offset
    val end = offset + length
    while (i < end) {
        val c = bytes[i].toInt() and 0xFF
        when {
            c < 0x80 -> { out[n++] = c.toChar(); i++ }
            c and 0xE0 == 0xC0 -> {
                out[n++] = (((c and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar(); i += 2
            }
            else -> {
                out[n++] = (((c and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or
                    (bytes[i + 2].toInt() and 0x3F)).toChar()
                i += 3
            }
        }
    }
    return out.concatToString(0, n)
}
