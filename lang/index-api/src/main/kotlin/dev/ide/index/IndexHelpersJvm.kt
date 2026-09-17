package dev.ide.index

/**
 * Normalize a jar path to the stable string the class-locator index (producer) and a name environment
 * (consumer) both match on. A shared producer↔consumer contract, so it lives here (neutral) rather than in
 * any one language backend.
 */
fun normalizedJarKey(p: java.nio.file.Path): String =
    runCatching { p.toAbsolutePath().normalize().toString() }.getOrDefault(p.toString())

/** [normalizedJarKey] for the string form an [IndexInput] now carries. */
fun normalizedJarKey(path: String): String =
    runCatching { java.nio.file.Paths.get(path) }.getOrNull()?.let { normalizedJarKey(it) } ?: path
