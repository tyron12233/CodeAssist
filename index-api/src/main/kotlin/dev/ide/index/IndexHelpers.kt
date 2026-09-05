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

/**
 * FQN + simple name from a NESTED class-file entry, in the DOTTED form an `import` line spells:
 * "android/widget/LinearLayout$LayoutParams.class" -> "android.widget.LinearLayout.LayoutParams",
 * "LayoutParams". Null when the entry holds no `$` ([classEntryToFqn] covers a top-level type) or names a
 * SYNTHETIC class rather than a declared nested one: every `$`-separated segment after the first must start
 * like an identifier, which rejects an anonymous class (`Outer$1`), a lambda/inline artefact
 * (`Outer$foo$1`, `Outer$$inlined$x$1`) and a compiler-generated `sam$…$0`.
 *
 * A nested type is importable and completable by its own simple name (`Map.Entry`,
 * `LinearLayout.LayoutParams`, `Animator.AnimatorListener`), so the class-NAME index keys it that way; the
 * dotted FQN is what both the Java and the Kotlin import machinery write and resolve. The `$`-free
 * [classEntryToFqn] stays the contract for the class LOCATOR and package indexes, whose consumers (the ecj
 * name environment, package-member completion) are top-level-only by design.
 */
fun nestedClassEntryToFqn(entry: String): Pair<String, String>? {
    if (!entry.endsWith(".class")) return null
    val noExt = entry.removeSuffix(".class")
    val slash = noExt.lastIndexOf('/')
    val names = noExt.substring(slash + 1).split('$')
    if (names.size < 2) return null
    if (names.any { it.isEmpty() || !(it[0].isLetter() || it[0] == '_') }) return null
    val pkg = if (slash < 0) "" else noExt.substring(0, slash).replace('/', '.').trim('.')
    val fqn = (if (pkg.isEmpty()) names else listOf(pkg) + names).joinToString(".")
    return fqn to names.last()
}

/** All package prefixes of a class FQN: "java.util.List" -> ["java","java.util"]. */
fun packagePrefixes(fqn: String): List<String> {
    val pkg = fqn.substringBeforeLast('.', "")
    if (pkg.isEmpty()) return emptyList()
    val parts = pkg.split('.')
    return (1..parts.size).map { parts.subList(0, it).joinToString(".") }
}
