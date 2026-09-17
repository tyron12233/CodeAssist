package dev.ide.kotlin.classfile

/**
 * An environment variable, or null when it is unset.
 *
 * Test-only, and only so that a benchmark can be pointed at a jar that is far too big to embed. The module
 * itself reads no environment.
 */
expect fun readEnv(name: String): String?
