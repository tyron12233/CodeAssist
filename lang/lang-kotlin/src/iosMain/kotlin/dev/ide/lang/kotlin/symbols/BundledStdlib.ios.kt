package dev.ide.lang.kotlin.symbols

/**
 * No bundled jar on iOS yet.
 *
 * There is no classpath to carry one as a resource, and nothing on this platform compiles Kotlin, so the
 * stdlib reaches the symbol service the ordinary way: as a declared classpath entry the host hands in.
 */
internal actual fun bundledStdlibJarPath(): String? = null
