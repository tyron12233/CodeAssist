package dev.ide.lang.kotlin.parse

import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult

/**
 * Parsing strategy: full reparse of the edited file to PSI on every change. Parsing one file is fast and
 * PSI parsing is the only cost (resolution is skipped entirely), so incremental reparse is not implemented;
 * the backend does not advertise [BackendCapability.INCREMENTAL].
 *
 * The reparse still honors the [IncrementalParser] contract: it returns a fresh tree at the new version,
 * reporting the whole file as the reparsed range and zero reused subtrees.
 */
class KotlinIncrementalParser : IncrementalParser {

    override fun parseFull(snapshot: DocumentSnapshot): ParsedFile {
        val ktFile = KotlinParserHost.parse(snapshot.file.name, snapshot.text)
        return KotlinParsedFile(ktFile, snapshot.file, snapshot.version)
    }

    override fun reparse(
        previous: ParsedFile,
        newSnapshot: DocumentSnapshot,
        edits: List<DocumentEdit>,
    ): ReparseResult {
        val tree = parseFull(newSnapshot)
        return ReparseResult(
            tree = tree,
            reparsedRange = TextRange(0, newSnapshot.length()),
            reusedSubtrees = 0,
        )
    }
}
