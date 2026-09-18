@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ide.templates

import dev.ide.platform.createDirectories
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

private var counter = 0

actual fun scratchDir(name: String): String {
    val path = NSTemporaryDirectory().trimEnd('/') + "/$name-${++counter}"
    createDirectories(path)
    return path
}

actual fun deleteTree(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}
