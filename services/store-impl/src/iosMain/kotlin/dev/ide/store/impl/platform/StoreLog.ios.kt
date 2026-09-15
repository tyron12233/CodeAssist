package dev.ide.store.impl.platform

/**
 * `println`, which reaches the Xcode console and `simctl launch --console-pty`.
 *
 * The framework's logging facade is Kotlin/JVM, and there are two calls in this module, both about a
 * screenshot that would not upload. Routing them through a second logging abstraction would cost more
 * than it explains.
 */
internal actual fun storeLog(tag: String, message: String, error: Throwable?) {
    println("W/$tag: $message" + (error?.let { " (${it.message})" } ?: ""))
}
