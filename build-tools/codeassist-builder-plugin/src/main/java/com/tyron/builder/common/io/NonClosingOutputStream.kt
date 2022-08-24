@file:JvmName("NonClosingStreams")
package com.tyron.builder.common.io

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun close() {
        flush()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }
}

/**
 * Wrap the given [OutputStream] to avoid closing the underlying output stream.
 *
 * When [close] is requested, only [flush] is called on the underlying stream.
 */
fun OutputStream.nonClosing(): OutputStream = NonClosingOutputStream(this)

/**
 * Wrap the given `InputStream` to avoid closing the underlying stream.
 *
 * When [close] is requested, nothing happens to the underlying stream.
 *
 * See [NonClosingInputStream]
 */
fun InputStream.nonClosing(): InputStream {
    return NonClosingInputStream(this).apply {
        closeBehavior = NonClosingInputStream.CloseBehavior.IGNORE
    }
}