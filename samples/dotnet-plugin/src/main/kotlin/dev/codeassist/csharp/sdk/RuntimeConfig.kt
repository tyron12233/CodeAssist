package dev.codeassist.csharp.sdk

import java.nio.file.Files
import java.nio.file.Path

/** A `.runtimeconfig.json` naming the framework a program runs on: what `dotnet app.dll` reads beside `app.dll`. */
internal const val RUNTIME_CONFIG =
    """{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"}}}"""

/**
 * Makes file hold RUNTIME_CONFIG, writing only when it differs. Returns whether it wrote.
 *
 * A tool built under an earlier runtime keeps the config it was given then, which may name a framework the
 * current tree does not have, and an unchanged source does not rebuild it. So the config is checked on its own.
 */
internal fun ensureRuntimeConfig(file: Path): Boolean {
    val current = runCatching {
        String(Files.readAllBytes(file), Charsets.UTF_8)
    }.getOrNull()
    if (current == RUNTIME_CONFIG) return false
    Files.write(file, RUNTIME_CONFIG.toByteArray(Charsets.UTF_8))
    return true
}
