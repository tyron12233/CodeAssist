package dev.ide.lang.kotlin.symbols

/**
 * No opt-in scan on iOS.
 *
 * `:kotlin-classfile` reads annotation DESCRIPTORS but not their values, and a `@RequiresOptIn` marker is
 * defined by a value (its level). Answering null means the opt-in diagnostic reports nothing for library
 * declarations here, which is the same thing it does for a class it cannot find: a missing warning, never a
 * wrong one.
 */
internal actual fun scanOptIn(classBytes: ByteArray): KotlinOptIn.OptInScan? = null
