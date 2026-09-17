package dev.ide.index.impl

internal actual fun scratchPath(name: String): String =
    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "index-impl-$name").toString()
