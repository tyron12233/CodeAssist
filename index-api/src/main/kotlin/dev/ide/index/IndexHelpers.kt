package dev.ide.index

/**
 * Normalize a jar path to the stable string the class-locator index (producer) and a name environment
 * (consumer) both match on. A shared producer↔consumer contract, so it lives here (neutral) rather than in
 * any one language backend.
 */
fun normalizedJarKey(p: java.nio.file.Path): String =
    runCatching { p.toAbsolutePath().normalize().toString() }.getOrDefault(p.toString())

/** FQN + simple name from a class-file entry path ("java/util/List.class" -> "java.util.List","List"), or null for nested/synthetic/info. */
fun classEntryToFqn(entry: String): Pair<String, String>? {
    if (!entry.endsWith(".class")) return null
    val noExt = entry.removeSuffix(".class")
    if (noExt.endsWith("package-info") || noExt.endsWith("module-info") || noExt.contains('$')) return null
    val fqn = noExt.replace('/', '.').trimStart('.')
    val simple = fqn.substringAfterLast('.')
    if (simple.isEmpty() || !(simple[0].isLetter() || simple[0] == '_')) return null
    return fqn to simple
}

/** All package prefixes of a class FQN: "java.util.List" -> ["java","java.util"]. */
fun packagePrefixes(fqn: String): List<String> {
    val pkg = fqn.substringBeforeLast('.', "")
    if (pkg.isEmpty()) return emptyList()
    val parts = pkg.split('.')
    return (1..parts.size).map { parts.subList(0, it).joinToString(".") }
}
