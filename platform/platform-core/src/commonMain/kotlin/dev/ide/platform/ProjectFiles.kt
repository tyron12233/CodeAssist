package dev.ide.platform

/**
 * Which files in a project are build output and which are the project's own, for everything that copies a
 * project somewhere: the store packager and installer, backups, `.caproj` packages, Gradle export and imports.
 *
 * Deciding by name alone is what broke this. Every copier used to drop any path with a segment called `build`,
 * so a package `com/example/build/` vanished, and the store also dropped every `.so`, `.aar` and `.jar`, so a
 * project's own `libs/` and `jniLibs/` vanished with them. The rule now asks where a file is, not only what it
 * is called:
 *
 *  - A **source tree** (any path under a `src` directory) belongs to the project, whatever is in it: a package
 *    named `build`, an `.apk` in `assets/`, a `.class` in `resources/`.
 *  - Outside one, a directory named like a build output ([BUILD_OUTPUT_DIRS]) is build output, and so is an
 *    installable package or compiled class ([BUILD_PRODUCT_SUFFIXES]) lying anywhere.
 *  - Libraries (`.so`, `.aar`, `.jar`) are never build output by extension. Outside a build-output directory
 *    they are inputs the project ships with: `libs/`, `jniLibs/`, the Gradle wrapper jar.
 *
 * Paths are project-relative and `/`- or `\`-separated.
 */
object ProjectFiles {

    /** Directory names a build writes, when they are not inside a source tree. */
    val BUILD_OUTPUT_DIRS: Set<String> = setOf(
        "build", "bin", "out", ".externalNativeBuild", ".cxx", "captures", "node_modules",
    )

    /** Tool and editor state, never project content wherever it sits. */
    val TOOL_STATE_DIRS: Set<String> = setOf(".gradle", ".kotlin", ".idea", ".git")

    /** What a build produces and nothing consumes as an input, unless it sits inside a source tree. */
    val BUILD_PRODUCT_SUFFIXES: List<String> = listOf(".apk", ".apks", ".aab", ".xapk", ".dex", ".class")

    /**
     * Whether the file (or, with [isDirectory], the directory) at [relativePath] is build output.
     *
     * [outputDirs] are the directory names that count as output outside a source tree; a copier of the user's
     * own data (a backup, an import) passes a narrower set than the store does. [products] also counts a stray
     * [BUILD_PRODUCT_SUFFIXES] file outside a source tree; a backup that keeps the user's built APKs passes false.
     */
    fun isBuildOutput(
        relativePath: String,
        isDirectory: Boolean = relativePath.endsWith("/") || relativePath.endsWith("\\"),
        outputDirs: Set<String> = BUILD_OUTPUT_DIRS,
        products: Boolean = true,
    ): Boolean {
        val segments = segments(relativePath)
        if (segments.isEmpty()) return false
        val dirCount = if (isDirectory) segments.size else segments.size - 1
        for (i in 0 until dirCount) {
            val s = segments[i]
            // Everything under a source tree is the project's own; nothing below can be build output.
            if (s == SOURCE_DIR) return false
            if (s in outputDirs) return true
        }
        if (isDirectory || !products) return false
        val name = segments.last()
        return BUILD_PRODUCT_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }
    }

    /** Whether [relativePath] is inside, or is, tool or editor state ([TOOL_STATE_DIRS]). */
    fun isToolState(relativePath: String): Boolean = segments(relativePath).any { it in TOOL_STATE_DIRS }

    /** Whether [relativePath] lies inside a source tree (below a `src` directory). */
    fun inSourceTree(relativePath: String): Boolean = segments(relativePath).dropLast(1).contains(SOURCE_DIR)

    private const val SOURCE_DIR = "src"

    private fun segments(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
}
