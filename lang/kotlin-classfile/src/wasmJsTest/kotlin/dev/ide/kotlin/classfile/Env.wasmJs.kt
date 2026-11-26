package dev.ide.kotlin.classfile

/** A browser build has no process environment. */
actual fun readEnv(name: String): String? = null
