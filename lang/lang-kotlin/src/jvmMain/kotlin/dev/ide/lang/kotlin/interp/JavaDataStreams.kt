package dev.ide.lang.kotlin.interp

import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import java.io.DataInput
import java.io.DataOutput

/** A [DataWriter] over a `java.io.DataOutput`, for JVM callers of [ResolvedTreeCodec] that hold a stream. */
class JavaDataWriter(private val out: DataOutput) : DataWriter {
    override fun writeByte(value: Int) = out.writeByte(value)
    override fun writeBoolean(value: Boolean) = out.writeBoolean(value)
    override fun writeShort(value: Int) = out.writeShort(value)
    override fun writeInt(value: Int) = out.writeInt(value)
    override fun writeLong(value: Long) = out.writeLong(value)
    override fun writeUTF(value: String) = out.writeUTF(value)
}

/** A [DataReader] over a `java.io.DataInput`. */
class JavaDataReader(private val input: DataInput) : DataReader {
    override fun readByte(): Int = input.readByte().toInt()
    override fun readUnsignedByte(): Int = input.readUnsignedByte()
    override fun readBoolean(): Boolean = input.readBoolean()
    override fun readShort(): Int = input.readUnsignedShort()
    override fun readInt(): Int = input.readInt()
    override fun readLong(): Long = input.readLong()
    override fun readUTF(): String = input.readUTF()
}
