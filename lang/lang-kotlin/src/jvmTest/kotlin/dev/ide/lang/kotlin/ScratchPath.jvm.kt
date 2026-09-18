package dev.ide.lang.kotlin

internal actual fun scratchPath(name: String): String =
    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "lang-kotlin-$name").toString()

internal actual fun nowSuffix(): String = System.nanoTime().toString()
