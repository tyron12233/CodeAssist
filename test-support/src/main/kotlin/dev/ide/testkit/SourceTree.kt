package dev.ide.testkit

import java.nio.file.Files
import java.nio.file.Path

/**
 * Write [content] to [rel] under this directory, creating parent directories. By default the content is
 * [String.trimIndent]ed (matching the ubiquitous `private fun write(root, rel, content)` helper); pass
 * `trim = false` to write it verbatim. Returns the written file path.
 */
fun Path.writeSource(rel: String, content: String, trim: Boolean = true): Path {
    val file = resolve(rel)
    Files.createDirectories(file.parent ?: this)
    Files.writeString(file, if (trim) content.trimIndent() else content)
    return file
}

/** A small DSL for seeding a source tree: `dir.sourceTree { java("a/B.java", "...") ; xml("res/x.xml", "...") }`. */
class SourceTree(val root: Path) {
    fun file(rel: String, content: String, trim: Boolean = true): Path = root.writeSource(rel, content, trim)
    fun java(rel: String, content: String): Path = file(rel, content)
    fun kt(rel: String, content: String): Path = file(rel, content)
    fun xml(rel: String, content: String): Path = file(rel, content)
}

/** Seed files under this directory via the [SourceTree] DSL; returns this directory. */
fun Path.sourceTree(block: SourceTree.() -> Unit): Path {
    SourceTree(this).block()
    return this
}
