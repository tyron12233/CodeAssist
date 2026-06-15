package dev.ide.lang.kotlin.symbols

/**
 * Kotlin's default imports: the star-imports implicitly in scope in every `.kt` file on the JVM, per the
 * language spec. Without these, `println`, `listOf`, `String`, `mutableListOf`, `Regex`, etc. would not
 * resolve, so name/type completion would miss most of the stdlib.
 *
 * These are on-demand (star) imports: a simple name is resolvable if it lives in any of these packages.
 * Order matters only for shadowing, which the resolver does not model finely.
 */
object DefaultImports {
    /** Packages whose members are visible by simple name everywhere (JVM target). */
    val STAR_PACKAGES: List<String> = listOf(
        "kotlin",
        "kotlin.annotation",
        "kotlin.collections",
        "kotlin.comparisons",
        "kotlin.io",
        "kotlin.ranges",
        "kotlin.sequences",
        "kotlin.text",
        // JVM-platform default imports
        "java.lang",
        "kotlin.jvm",
    )

    /** True if [packageFqn] is brought in by a Kotlin default import (so a simple name from it resolves). */
    fun isDefaultImported(packageFqn: String): Boolean = packageFqn in STAR_PACKAGES
}
