package dev.ide.lang.java

/**
 * Rewriting a Java file's `package` statement when the file is relocated, or when correcting a package that
 * does not match the file's directory. Returns plain text so callers stay off the JDT PSI. The Java package
 * grammar is simple enough (a single leading `package a.b.c;` before any type) that a regex is exact here —
 * the parallel to Kotlin's `KotlinPackageRewrite`, which needs the PSI for its `@file:` handling.
 */
object JavaPackageRewrite {

    /** [updatedText] with the package set to the target, plus the file's prior [oldPackage] (`""` = default). */
    class Result(val updatedText: String, val oldPackage: String)

    // The leading `package a.b.c;` (Java requires it before every type declaration; the first match wins).
    private val PACKAGE_DECL = Regex("""(?m)^[ \t]*package[ \t]+([\w.]+)[ \t]*;[ \t]*\r?\n?""")

    /**
     * [Result] of setting [text]'s package to [newPackage] (`""` = the default package), or null when the file
     * already declares [newPackage]. Replaces the existing name, drops the statement for the default package,
     * or inserts `package …;` at the top when there is none.
     */
    fun rewrite(text: String, newPackage: String): Result? {
        val existing = PACKAGE_DECL.find(text)
        val oldPackage = existing?.groupValues?.get(1) ?: ""
        if (oldPackage == newPackage) return null
        val updated = when {
            existing != null && newPackage.isEmpty() -> text.removeRange(existing.range) // → default: drop the line
            existing != null -> text.replaceRange(existing.groups[1]!!.range, newPackage)
            else -> "package $newPackage;\n\n$text" // no statement, and a named package is wanted → insert one
        }
        return Result(updated, oldPackage)
    }
}
