package dev.ide.index.impl

import dev.ide.platform.DataReader
import dev.ide.platform.DataWriter
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The plain `java.io` adapters, for the one path that still has a stream in hand.
 *
 * The segment format's own writer no longer does: it goes through the portable byte plumbing so it can run
 * where `java.io` does not exist. What is left is the source-entry cache in [IndexServiceImpl], which writes
 * and reads values straight onto a `DataOutputStream` with no interning, and which has not crossed yet.
 * Same six operations, verbatim.
 */
internal class StreamDataWriter(private val d: DataOutputStream) : DataWriter {
    override fun writeUTF(value: String) = d.writeUTF(value)
    override fun writeBoolean(value: Boolean) = d.writeBoolean(value)
    override fun writeByte(value: Int) = d.writeByte(value)
    override fun writeShort(value: Int) = d.writeShort(value)
    override fun writeInt(value: Int) = d.writeInt(value)
    override fun writeLong(value: Long) = d.writeLong(value)
}

internal class StreamDataReader(private val d: DataInputStream) : DataReader {
    override fun readUTF(): String = d.readUTF()
    override fun readBoolean(): Boolean = d.readBoolean()
    override fun readByte(): Int = d.readByte().toInt()
    override fun readUnsignedByte(): Int = d.readUnsignedByte()
    override fun readShort(): Int = d.readShort().toInt()
    override fun readInt(): Int = d.readInt()
    override fun readLong(): Long = d.readLong()
}

/** The runtime's own errors, which a value-payload guard must never swallow. */
internal actual fun rethrowIfFatal(t: Throwable) {
    if (t is VirtualMachineError) throw t
}
