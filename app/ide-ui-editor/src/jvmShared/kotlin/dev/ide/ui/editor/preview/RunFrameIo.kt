package dev.ide.ui.editor.preview

import java.io.File

actual fun readRunFrame(path: String): ByteArray? {
    val file = File(path)
    val bytes = runCatching { file.readBytes() }.getOrNull()
    runCatching { file.delete() }
    return bytes?.takeIf { it.isNotEmpty() }
}
