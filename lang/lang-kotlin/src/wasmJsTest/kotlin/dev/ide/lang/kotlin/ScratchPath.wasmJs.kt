package dev.ide.lang.kotlin

import dev.ide.platform.epochMillis

// The portable file seam is the in-memory filesystem here, so any absolute path is writable.
internal actual fun scratchPath(name: String): String = "/tmp/lang-kotlin-$name"

private var counter = 0

internal actual fun nowSuffix(): String = "${epochMillis()}-${counter++}"
