package dev.ide.store.impl.platform

import java.io.File

actual object StoreFs {
    actual fun exists(path: String): Boolean = File(path).exists()
    actual fun isDirectory(path: String): Boolean = File(path).isDirectory
    actual fun isFile(path: String): Boolean = File(path).isFile
    actual fun size(path: String): Long = File(path).length()
    actual fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }
    actual fun mkdirsForFile(path: String) { File(path).parentFile?.mkdirs() }
    actual fun delete(path: String): Boolean = File(path).delete()
    actual fun deleteRecursively(path: String): Boolean = File(path).deleteRecursively()
    actual fun canonicalPath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
    actual fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))
    actual fun copyRecursively(from: String, to: String): Boolean =
        runCatching { File(from).copyRecursively(File(to), overwrite = false) }.getOrDefault(false)
    actual fun readBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()
    actual fun writeBytes(path: String, bytes: ByteArray): Boolean =
        runCatching { File(path).writeBytes(bytes); true }.getOrDefault(false)
    actual fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()
    actual fun writeText(path: String, text: String): Boolean =
        runCatching { File(path).writeText(text); true }.getOrDefault(false)
    actual fun list(path: String): List<String> = File(path).list()?.toList().orEmpty()
    actual fun modifiedMillis(path: String): Long = File(path).lastModified()
    actual fun tempPath(prefix: String, suffix: String): String {
        val file = File.createTempFile(prefix, suffix)
        file.delete()
        return file.absolutePath
    }
}
