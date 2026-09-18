package dev.ide.templates

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

actual fun scratchDir(name: String): String =
    Files.createTempDirectory(name).toAbsolutePath().toString()

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
actual fun deleteTree(path: String) {
    runCatching { Path.of(path).deleteRecursively() }
}
