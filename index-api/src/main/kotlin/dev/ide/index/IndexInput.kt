package dev.ide.index

import dev.ide.lang.dom.ParsedFile
import dev.ide.platform.ContentHash
import java.nio.file.Path

/** One unit to index: a class-file entry, a source file, … Cheap fields are eager; bytes/dom are lazy. */
interface IndexInput {
    val origin: IndexOrigin
    val contentHash: ContentHash

    /** Logical name of the unit: a class entry path ("java/util/List.class") or a source path. */
    val unitName: String?

    /** Source file path for project-source inputs (null for library/SDK units). */
    val sourcePath: Path?

    /** The interned, per-project-stable file id of a project-SOURCE unit (see the index's file-id table), or
     *  -1 for library/SDK units that have no project file. Lets a source value store the id instead of
     *  repeating the full path string; resolve it back with [IndexService.filePath]. */
    val fileId: Int get() = -1
    fun bytes(): ByteArray
    fun text(): String?
    fun dom(): ParsedFile?

    /**
     * Per-input memo shared across every index extension that processes THIS input in a pass — the same role
     * as IntelliJ's `FileContent` (one input instance is handed to all extensions for a file). An extension
     * computes an expensive per-file artifact (a parsed AST, a distilled declaration model) under a stable
     * [key] once; every other extension asking for the same [key] reuses it, so a file is parsed once, not
     * once per index. [compute] may return null (a failed parse) and the null is cached too. The default is
     * no caching (each call recomputes) — inputs that aren't shared across extensions need nothing more.
     */
    fun <T> shared(key: String, compute: () -> T): T = compute()
}
