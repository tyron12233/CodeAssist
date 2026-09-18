package dev.ide.platform.log

/** Either flag on the launcher turns tracing on, and a stored preference cannot turn it back off. */
internal actual fun perfTraceLaunchFlag(): Boolean = runCatching {
    System.getProperty("ide.editor.perf")?.toBoolean() == true ||
        System.getProperty("ide.kotlin.perf")?.toBoolean() == true
}.getOrNull() ?: false
