package dev.codeassist.swift

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.PlatformKind
import dev.ide.model.SourceSetTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * A Swift package as a module, laid out as SwiftPM lays one out: `Package.swift` at its root, each target's sources
 * in `Sources/<target>/`, tests in `Tests/`. The Project view shows those under one role of this plugin's own rather
 * than as JVM source roots.
 */
object SwiftModuleType : ModuleType {
    const val ID = "swift-package"

    override val id = ID
    override val displayName = "Swift package"
    override val platform: PlatformKind get() = SWIFT_PLATFORM
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("Sources" to setOf(SWIFT_SOURCE))),
        SourceSetTemplate("test", DependencyScope.TEST_IMPLEMENTATION, mapOf("Tests" to setOf(SWIFT_SOURCE))),
    )
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)

    /** A directory of Swift code in a package. */
    val SWIFT_SOURCE = ContentRole("swift-source")

    /** Swift compiles against its own standard library and Foundation, never android.jar or a JDK. */
    val SWIFT_PLATFORM = PlatformKind("SWIFT")
}

/** What the plugin needs to know about a package on disk. Pure file reads and text; nothing here runs Swift. */
object SwiftPackages {
    const val MANIFEST = "Package.swift"
    const val SOURCES = "Sources"

    /** Where Run keeps what it generates, inside the package (SwiftPM's own build directory, which `.gitignore` has). */
    const val BUILD_DIR = ".build/codeassist"

    /**
     * The package's executable targets: each directory directly under `Sources/` that holds a `main.swift`, by name.
     * SwiftPM also makes a target with an `@main` type executable; Run offers those once it reads the manifest.
     */
    fun executables(pkg: Path): List<String> {
        val sources = pkg.resolve(SOURCES)
        if (!Files.isDirectory(sources)) return emptyList()
        return Files.list(sources).use { dirs ->
            dirs.filter { Files.isRegularFile(it.resolve("main.swift")) }.map { it.fileName.toString() }.sorted()
                .collect(Collectors.toList())
        }
    }

    /** The package's SwiftUI app targets: directories under `Sources/` without a `main.swift` whose code has an `@main` App. */
    fun apps(pkg: Path): List<String> {
        val sources = pkg.resolve(SOURCES)
        if (!Files.isDirectory(sources)) return emptyList()
        return Files.list(sources).use { dirs ->
            dirs.filter { Files.isDirectory(it) && !Files.exists(it.resolve("main.swift")) }.collect(Collectors.toList())
        }.filter { dir ->
            SwiftPreviewSource.swiftFiles(dir).any { f -> runCatching { SwiftPreviewSource.appType(String(Files.readAllBytes(f))) }.getOrNull() != null }
        }.map { it.fileName.toString() }.sorted()
    }

    /** Every `.swift` file of [target], sorted so a Run compiles them in the same order each time. */
    fun sources(pkg: Path, target: String): List<Path> {
        val dir = pkg.resolve(SOURCES).resolve(target)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir).use { files ->
            files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".swift") }.sorted()
                .collect(Collectors.toList())
        }
    }

    /** A Swift identifier for [name] (a module name has to be one): `my-tool` becomes `my_tool`. */
    fun moduleName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        return when {
            cleaned.isEmpty() -> "main"
            cleaned.first().isDigit() -> "_$cleaned"
            else -> cleaned
        }
    }

    /**
     * The `main.swift` a Run compiles in place of the program's own: the same text, with standard output made
     * unbuffered on its first line. Run's console reads the program through a pipe, and C's stdio fully buffers a
     * pipe, so `print("Name? ", terminator: "")` before a `readLine()` would show only after the answer was typed.
     *
     * On the SAME line, so every line keeps its number and the compiler's messages point at the program's own lines
     * (only line 1's columns move). A `#!` line, which may only open a file, gives its place to the prologue.
     * `#sourceLocation` would keep line 1's columns too, but costs a warning in every Run's output.
     */
    fun runMain(original: String): String {
        val text = original.removePrefix("﻿")
        val firstLineEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        val firstLine = text.substring(0, firstLineEnd)
        val rest = text.substring(firstLineEnd)
        // A first line that is a compiler directive (#if) has to start its line; then the prologue gets its own line
        // and every line moves down by one: rare, and better than a program that does not compile.
        return when {
            firstLine.startsWith("#!") -> PROLOGUE + rest
            firstLine.trimStart().startsWith("#") -> PROLOGUE + "\n" + text
            else -> "$PROLOGUE; $firstLine$rest"
        }
    }

    /** What [runMain] puts first. `Android` is the C library module of Swift on Android, where `setvbuf` is. */
    const val PROLOGUE = "import Android; setvbuf(stdout, nil, _IONBF, 0)"
}
