package dev.ide.kotlin.classfile

import java.util.zip.Inflater

internal actual fun platformInflate(input: ByteArray, expectedSize: Int): ByteArray? {
    val inflater = Inflater(/* nowrap = */ true)
    return try {
        inflater.setInput(input)
        val output = ByteArray(expectedSize)
        var written = 0
        while (written < expectedSize) {
            val n = inflater.inflate(output, written, expectedSize - written)
            if (n == 0) {
                if (inflater.finished() || inflater.needsDictionary()) break
                // A raw stream may need one byte past its end before zlib reports it finished.
                if (inflater.needsInput()) inflater.setInput(ByteArray(1)) else break
            }
            written += n
        }
        if (written == expectedSize) output else null
    } catch (_: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}
