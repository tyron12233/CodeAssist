package dev.ide.vm.jdk

import dev.ide.vm.*

/** The bytes of a `ByteArrayInputStream`, and where reading is. */
internal class ByteInput(val bytes: ByteArray, var pos: Int, val end: Int) {
    var mark = pos
}

/**
 * `java.io` streams over memory, and the stream classes that wrap them. Nothing here reaches a file
 * system: a preview reads its inputs from what the host hands it.
 */
internal fun ClassDefs.registerIo() {
    define("java/io/DataInput") { asInterface() }
    define("java/io/DataOutput") { asInterface() }

    define("java/io/InputStream") {
        asAbstract()
        implements("java/io/Closeable")
        ctor("()V") {}
        method("read", "([B)I") { ret(readInto(vm, r(0)!!, r(1) as ByteArray, 0, (r(1) as ByteArray).size)) }
        method("read", "([BII)I") { ret(readInto(vm, r(0)!!, r(1) as ByteArray, i(2), i(3))) }
        method("readAllBytes", "()[B") {
            val out = ArrayList<Byte>()
            while (true) { val b = vm.callVirtual(r(0), "read", "()I") as Int; if (b < 0) break; out.add(b.toByte()) }
            retRef(out.toByteArray())
        }
        method("skip", "(J)J") {
            var n = 0L
            while (n < l(1) && (vm.callVirtual(r(0), "read", "()I") as Int) >= 0) n++
            ret(n)
        }
        method("available", "()I") { ret(0) }
        method("close", "()V") {}
        method("markSupported", "()Z") { ret(false) }
    }
    define("java/io/OutputStream") {
        asAbstract()
        implements("java/io/Closeable", "java/io/Flushable")
        ctor("()V") {}
        method("write", "([B)V") { for (b in r(1) as ByteArray) vm.callVirtual(r(0), "write", "(I)V", b.toInt()) }
        method("write", "([BII)V") {
            val a = r(1) as ByteArray
            for (k in i(2) until i(2) + i(3)) vm.callVirtual(r(0), "write", "(I)V", a[k].toInt())
        }
        method("flush", "()V") {}
        method("close", "()V") {}
    }

    define("java/io/ByteArrayInputStream") {
        superName = "java/io/InputStream"
        fun Call.input(): ByteInput = self().native as ByteInput
        ctor("([B)V") { val b = r(1) as ByteArray; self().native = ByteInput(b, 0, b.size) }
        ctor("([BII)V") { val b = r(1) as ByteArray; self().native = ByteInput(b, i(2), minOf(b.size, i(2) + i(3))) }
        method("read", "()I") { val s = input(); ret(if (s.pos < s.end) s.bytes[s.pos++].toInt() and 0xFF else -1) }
        method("read", "([BII)I") {
            val s = input()
            if (s.pos >= s.end) { ret(-1); return@method }
            val n = minOf(i(3), s.end - s.pos)
            s.bytes.copyInto(r(1) as ByteArray, i(2), s.pos, s.pos + n)
            s.pos += n
            ret(n)
        }
        method("available", "()I") { val s = input(); ret(s.end - s.pos) }
        method("skip", "(J)J") { val s = input(); val n = minOf(l(1), (s.end - s.pos).toLong()).coerceAtLeast(0); s.pos += n.toInt(); ret(n) }
        method("readAllBytes", "()[B") { val s = input(); val out = s.bytes.copyOfRange(s.pos, s.end); s.pos = s.end; retRef(out) }
        method("markSupported", "()Z") { ret(true) }
        method("mark", "(I)V") { input().mark = input().pos }
        method("reset", "()V") { input().pos = input().mark }
    }
    define("java/io/ByteArrayOutputStream") {
        superName = "java/io/OutputStream"
        fun Call.out(): ByteBuffer = self().native as ByteBuffer
        ctor("()V") { self().native = ByteBuffer() }
        ctor("(I)V") { self().native = ByteBuffer() }
        method("write", "(I)V") { out().add(i(1).toByte()) }
        method("write", "([BII)V") { out().add(r(1) as ByteArray, i(2), i(3)) }
        method("writeBytes", "([B)V") { val a = r(1) as ByteArray; out().add(a, 0, a.size) }
        method("toByteArray", "()[B") { retRef(out().toByteArray()) }
        method("size", "()I") { ret(out().size) }
        method("reset", "()V") { out().size = 0 }
        method("toString", "()Ljava/lang/String;") { retRef(out().toByteArray().decodeToString()) }
    }

    for ((name, parent) in listOf("java/io/FilterInputStream" to "java/io/InputStream", "java/io/FilterOutputStream" to "java/io/OutputStream")) {
        define(name) {
            superName = parent
            field("in", "L$parent;")
            ctor("(L$parent;)V") { self().refs[0] = r(1) }
            if (parent == "java/io/InputStream") {
                method("read", "()I") { ret(vm.callVirtual(self().refs[0], "read", "()I") as Int) }
                method("read", "([BII)I") { ret(readInto(vm, self().refs[0]!!, r(1) as ByteArray, i(2), i(3))) }
                method("available", "()I") { ret(vm.callVirtual(self().refs[0], "available", "()I") as Int) }
                method("close", "()V") { vm.callVirtual(self().refs[0], "close", "()V") }
            } else {
                method("write", "(I)V") { vm.callVirtual(self().refs[0], "write", "(I)V", i(1)) }
                method("flush", "()V") { vm.callVirtual(self().refs[0], "flush", "()V") }
                method("close", "()V") { vm.callVirtual(self().refs[0], "close", "()V") }
            }
        }
    }

    define("java/io/DataInputStream") {
        superName = "java/io/FilterInputStream"
        implements("java/io/DataInput")
        ctor("(Ljava/io/InputStream;)V") { self().refs[0] = r(1) }
        fun Call.byte(): Int {
            val src = self().refs[0]!!
            val direct = (src as? VmObject)?.native as? ByteInput
            val b = if (direct != null) (if (direct.pos < direct.end) direct.bytes[direct.pos++].toInt() and 0xFF else -1)
            else vm.callVirtual(src, "read", "()I") as Int
            if (b < 0) vm.throwVm("java/io/EOFException", null)
            return b
        }
        fun Call.int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun Call.long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)
        method("readInt", "()I") { ret(int()) }
        method("readLong", "()J") { ret(long()) }
        method("readShort", "()S") { ret(((byte() shl 8) or byte()).toShort().toInt()) }
        method("readUnsignedShort", "()I") { ret((byte() shl 8) or byte()) }
        method("readChar", "()C") { ret(((byte() shl 8) or byte()).toChar()) }
        method("readByte", "()B") { ret(byte().toByte().toInt()) }
        method("readUnsignedByte", "()I") { ret(byte()) }
        method("readBoolean", "()Z") { ret(byte() != 0) }
        method("readFloat", "()F") { ret(Float.fromBits(int())) }
        method("readDouble", "()D") { ret(Double.fromBits(long())) }
        method("readFully", "([B)V") { val a = r(1) as ByteArray; for (k in a.indices) a[k] = byte().toByte() }
        method("readFully", "([BII)V") { val a = r(1) as ByteArray; for (k in i(2) until i(2) + i(3)) a[k] = byte().toByte() }
        method("readUTF", "()Ljava/lang/String;") {
            val n = (byte() shl 8) or byte()
            retRef(decodeModifiedUtf8(ByteArray(n) { byte().toByte() }, 0, n))
        }
    }
    define("java/io/DataOutputStream") {
        superName = "java/io/FilterOutputStream"
        implements("java/io/DataOutput")
        ctor("(Ljava/io/OutputStream;)V") { self().refs[0] = r(1) }
        fun Call.byte(v: Int) {
            val dst = self().refs[0]!!
            val direct = (dst as? VmObject)?.native as? ByteBuffer
            if (direct != null) direct.add(v.toByte()) else vm.callVirtual(dst, "write", "(I)V", v and 0xFF)
        }
        fun Call.int(v: Int) { byte(v ushr 24); byte(v ushr 16); byte(v ushr 8); byte(v) }
        fun Call.long(v: Long) { int((v ushr 32).toInt()); int(v.toInt()) }
        method("writeInt", "(I)V") { int(i(1)) }
        method("writeLong", "(J)V") { long(l(1)) }
        method("writeShort", "(I)V") { byte(i(1) ushr 8); byte(i(1)) }
        method("writeChar", "(I)V") { byte(i(1) ushr 8); byte(i(1)) }
        method("writeByte", "(I)V") { byte(i(1)) }
        method("write", "(I)V") { byte(i(1)) }
        method("write", "([BII)V") { val a = r(1) as ByteArray; for (k in i(2) until i(2) + i(3)) byte(a[k].toInt()) }
        method("writeBoolean", "(Z)V") { byte(if (z(1)) 1 else 0) }
        method("writeFloat", "(F)V") { int(f(1).toRawBits()) }
        method("writeDouble", "(D)V") { long(d(1).toRawBits()) }
        method("writeUTF", "(Ljava/lang/String;)V") {
            val bytes = (r(1) as String).encodeToByteArray()
            byte(bytes.size ushr 8); byte(bytes.size); for (b in bytes) byte(b.toInt())
        }
        method("flush", "()V") {}
    }

    define("java/nio/charset/Charset") {
        asAbstract()
        implements("java/lang/Comparable")
        static("forName", "(Ljava/lang/String;)Ljava/nio/charset/Charset;") { retRef(vm.charset(r(0) as String)) }
        static("defaultCharset", "()Ljava/nio/charset/Charset;") { retRef(vm.charset("UTF-8")) }
        method("name", "()Ljava/lang/String;") { retRef(self().native as String) }
        method("displayName", "()Ljava/lang/String;") { retRef(self().native as String) }
        method("toString", "()Ljava/lang/String;") { retRef(self().native as String) }
    }
    define("java/nio/charset/HostCharset") { superName = "java/nio/charset/Charset" }
    define("java/nio/charset/StandardCharsets") {
        for (n in listOf("UTF_8", "US_ASCII", "ISO_8859_1", "UTF_16")) staticField(n, "Ljava/nio/charset/Charset;")
        onInit { cls ->
            for (n in listOf("UTF_8", "US_ASCII", "ISO_8859_1", "UTF_16")) cls.setStaticRef(n, "Ljava/nio/charset/Charset;", vm.charset(n.replace('_', '-')))
        }
    }
}

/** A growable byte buffer for `ByteArrayOutputStream`. */
internal class ByteBuffer {
    var data = ByteArray(32)
    var size = 0
    fun add(b: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = b
    }
    fun add(a: ByteArray, off: Int, len: Int) {
        while (size + len > data.size) data = data.copyOf(data.size * 2)
        a.copyInto(data, size, off, off + len)
        size += len
    }
    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** `InputStream.read(byte[], int, int)` through the stream's own `read()`. */
private fun readInto(vm: Vm, stream: Any, a: ByteArray, off: Int, len: Int): Int {
    if (len == 0) return 0
    var n = 0
    while (n < len) {
        val b = vm.callVirtual(stream, "read", "()I") as Int
        if (b < 0) break
        a[off + n++] = b.toByte()
    }
    return if (n == 0) -1 else n
}

/** One `Charset` object per name. Every charset decodes as UTF-8, which is what Kotlin code asks for. */
internal fun Vm.charset(name: String): VmObject = singletons.getOrPut("\u0000charset:${name.uppercase()}") {
    newVmObject("java/nio/charset/HostCharset").also { it.native = name.uppercase() }
}
