package dev.ide.lang.kotlin

/** A writable path for a test fixture, under whatever this platform calls its temporary directory. */
internal expect fun scratchPath(name: String): String
