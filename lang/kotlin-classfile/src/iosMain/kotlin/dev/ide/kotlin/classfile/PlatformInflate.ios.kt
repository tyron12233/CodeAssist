@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.kotlin.classfile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** The system zlib, which every iOS process already links. A negative window size means a raw stream. */
internal actual fun platformInflate(input: ByteArray, expectedSize: Int): ByteArray? {
    val output = ByteArray(expectedSize)
    val ok = memScoped {
        val stream = alloc<z_stream>()
        input.usePinned { source ->
            output.usePinned { target ->
                stream.zalloc = null
                stream.zfree = null
                stream.opaque = null
                stream.next_in = source.addressOf(0).reinterpret()
                stream.avail_in = input.size.convert()
                stream.next_out = target.addressOf(0).reinterpret()
                stream.avail_out = expectedSize.convert()
                if (inflateInit2_(stream.ptr, -MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt()) != Z_OK) {
                    return@usePinned false
                }
                try {
                    inflate(stream.ptr, Z_FINISH) == Z_STREAM_END && stream.total_out.toLong() == expectedSize.toLong()
                } finally {
                    inflateEnd(stream.ptr)
                }
            }
        }
    }
    return if (ok) output else null
}
