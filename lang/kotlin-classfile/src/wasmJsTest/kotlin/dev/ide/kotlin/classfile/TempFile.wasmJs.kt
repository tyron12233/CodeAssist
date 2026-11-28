package dev.ide.kotlin.classfile

import dev.ide.platform.writeFileAtomically

actual fun writeTempFile(name: String, bytes: ByteArray): String? {
    val path = "/tmp/$name.bin"
    return if (writeFileAtomically(path, bytes)) path else null
}
