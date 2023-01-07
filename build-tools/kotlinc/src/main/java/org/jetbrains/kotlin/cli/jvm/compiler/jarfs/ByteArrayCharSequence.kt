package org.jetbrains.kotlin.cli.jvm.compiler.jarfs

class ByteArrayCharSequence(
    private val bytes: ByteArray,
    private val start: Int = 0,
    private val end: Int = bytes.size
) : CharSequence {

    override fun hashCode(): Int {
        error("Do not try computing hashCode ByteArrayCharSequence")
    }

    override fun equals(other: Any?): Boolean {
        error("Do not try comparing ByteArrayCharSequence")
    }

    override val length get() = end - start

    override fun get(index: Int): Char = bytes[index + start].toChar()

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex == 0 && endIndex == length) return this
        return ByteArrayCharSequence(bytes, start + startIndex, start + endIndex)
    }

    override fun toString(): String {
        val chars = CharArray(length)

        for (i in 0 until length) {
            chars[i] = bytes[i + start].toInt().toChar()
        }

        return String(chars)
    }
}