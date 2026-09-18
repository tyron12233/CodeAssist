package dev.ide.platform

import java.io.File

actual fun writeTempFile(name: String, bytes: ByteArray): String? = runCatching {
    val file = File.createTempFile(name, ".bin")
    file.deleteOnExit()
    file.writeBytes(bytes)
    file.absolutePath
}.getOrNull()
