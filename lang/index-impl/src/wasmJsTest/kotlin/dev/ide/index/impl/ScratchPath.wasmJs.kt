package dev.ide.index.impl

// The portable file seam is the in-memory file system here, so any absolute path is writable.
internal actual fun scratchPath(name: String): String = "/tmp/index-impl-$name"
