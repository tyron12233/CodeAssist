package dev.ide.kotlin.classfile

actual fun readEnv(name: String): String? = System.getenv(name)
