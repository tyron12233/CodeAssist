package dev.ide.lang.java.index

import dev.ide.index.SourceDocValue

/**
 * Public entry to the lexer-based Java doc extractor ([JavaSourceDocScan]) for tools outside this module,
 * such as the generator of the bundled Android docs pack: owner type FQN -> each method's real parameter
 * names and javadoc, a type's own javadoc as the empty-name entry, a constructor keyed by the class's
 * simple name.
 */
object JavaSourceDocs {
    /** The docs declared in the Java source [text]; [rawDocs] keeps javadoc blocks unprocessed. */
    fun scan(text: CharSequence, rawDocs: Boolean = false): Map<String, Collection<SourceDocValue>> =
        JavaSourceDocScan.scan(text, rawDocs)

    /** [raw] javadoc as readable plain text: comment markers and `@` tag lines dropped. */
    fun clean(raw: String): String = JavaDoc.clean(raw)
}
