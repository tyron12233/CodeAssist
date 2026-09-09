package dev.ide.testkit

import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.vfs.VirtualFile

/**
 * A simple [DocumentSnapshot] over in-memory [text]. Replaces the many private `Doc` / `Snap` / `TestDoc` /
 * `SnippetDoc` / `Snapshot` stubs scattered across the language/analysis modules.
 */
class TestDocument(
    override val text: CharSequence,
    override val file: VirtualFile,
    override val version: Long = 1,
) : DocumentSnapshot {
    override fun length(): Int = text.length
}

/** A [TestDocument] over [text] for [file]. */
fun document(text: CharSequence, file: VirtualFile, version: Long = 1): TestDocument =
    TestDocument(text, file, version)

/** [text] with a caret marker removed, plus the [offset] where the marker was. */
data class CaretText(val text: String, val offset: Int)

/**
 * Parse a caret marker out of [marked]. Supports both conventions used in the repo: the single-char default
 * `|` and the explicit `|CARET|`. The FIRST occurrence is used and stripped.
 */
fun caret(marked: String, marker: String = "|"): CaretText {
    val offset = marked.indexOf(marker)
    require(offset >= 0) { "no caret marker `$marker` in text" }
    return CaretText(marked.removeRange(offset, offset + marker.length), offset)
}
