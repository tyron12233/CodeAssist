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
}
