package dev.ide.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors
import kotlin.io.path.isDirectory
import kotlin.io.path.name

actual fun fileInfo(path: String): FileInfo? = runCatching {
    val file: Path = Paths.get(path)
    if (!Files.exists(file)) return null
    FileInfo(
        path = file.toString(),
        isDirectory = file.isDirectory(),
        size = if (file.isDirectory()) 0 else Files.size(file),
        lastModified = Files.getLastModifiedTime(file).toMillis(),
    )
}.getOrNull()

actual fun listDirectory(path: String): List<String> = runCatching {
    // `Collectors.toList()`, NOT `Stream.toList()`: the latter is API 34 and this runs on ART from 26. It
    // dexes clean and throws `NoSuchMethodError` at the call site, and the `runCatching` here would swallow
    // that into an empty listing -- a classpath directory that silently contributes no classes at all.
    Files.list(Paths.get(path)).use { stream ->
        stream.map { it.toString() }.sorted().collect(Collectors.toList())
    }
}.getOrDefault(emptyList())

actual fun readFile(path: String): ByteArray? =
    runCatching { Files.readAllBytes(Paths.get(path)) }.getOrNull()

actual fun createDirectories(path: String): Boolean =
    runCatching { Files.createDirectories(Paths.get(path)) }.isSuccess

actual fun writeFileAtomically(path: String, bytes: ByteArray): Boolean = runCatching {
    val file = Paths.get(path)
    val directory = file.parent ?: return false
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, file.name, ".tmp")
    try {
        Files.write(temporary, bytes)
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        true
    } finally {
        Files.deleteIfExists(temporary)
    }
}.getOrDefault(false)

actual fun deleteFile(path: String): Boolean =
    runCatching { Files.deleteIfExists(Paths.get(path)) }.getOrDefault(false)
