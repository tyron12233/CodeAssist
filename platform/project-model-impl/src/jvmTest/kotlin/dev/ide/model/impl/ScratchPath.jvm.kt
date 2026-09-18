package dev.ide.model.impl

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.deleteRecursively

actual fun scratchDir(name: String): String =
    Files.createTempDirectory(name).toAbsolutePath().toString()

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
actual fun deleteTree(path: String) {
    runCatching { Paths.get(path).deleteRecursively() }
}
