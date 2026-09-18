package dev.ide.platform

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

actual fun openFileForWrite(path: String): FileSink? = runCatching {
    // Buffered, because the segment writer's callers write a handful of bytes at a time: a varint, a term,
    // a length. Unbuffered that is one syscall each.
    val out = BufferedOutputStream(Files.newOutputStream(Paths.get(path)))
    object : FileSink {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}.getOrNull()

actual fun moveFile(from: String, to: String): Boolean = runCatching {
    Files.move(
        Paths.get(from),
        Paths.get(to),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
    true
}.getOrElse {
    // ATOMIC_MOVE is refused across file systems; fall back to the replacing move, which is what the caller
    // would get from a plain rename anyway.
    runCatching {
        Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
