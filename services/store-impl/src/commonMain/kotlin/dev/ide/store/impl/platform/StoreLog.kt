package dev.ide.store.impl.platform

/**
 * A transport failure worth seeing in the console.
 *
 * The framework's `Log` facade is Kotlin/JVM (it is part of the published SPI), and this module now
 * compiles for iOS too, so the two lines in this module that log go through here instead.
 */
internal expect fun storeLog(tag: String, message: String, error: Throwable? = null)
