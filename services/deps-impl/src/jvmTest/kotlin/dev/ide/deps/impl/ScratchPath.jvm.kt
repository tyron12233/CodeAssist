package dev.ide.deps.impl

internal actual fun scratchPath(name: String): String =
    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "deps-impl-$name").toString()

internal actual fun nowSuffix(): String = System.nanoTime().toString()
