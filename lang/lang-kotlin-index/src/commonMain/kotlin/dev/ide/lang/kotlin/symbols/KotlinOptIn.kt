package dev.ide.lang.kotlin.symbols

/**
 * The opt-in markers on a class, read straight from its bytecode.
 *
 * The SHAPE is portable; the READER is not, and the reason is specific: this is the only decoder here that
 * needs annotation VALUES rather than annotation names, because a `@RequiresOptIn` marker declares a LEVEL,
 * and `:kotlin-classfile` collects annotation descriptors and skips their values.
 *
 * So the scan keeps its ASM implementation on the JVM, and the opt-in diagnostic is a desktop and Android
 * feature until either the portable reader decodes annotation values or the diagnostic does without a level.
 * `KotlinSymbolService` is the caller, and it treats a null scan as "nothing decided", which is the same
 * answer it already gives for a class that is not on the classpath.
 */
object KotlinOptIn {

    /** The opt-in-relevant annotation facts read from one class's bytecode. */
    class OptInScan(
        /** Annotation FQNs on the class declaration itself (a type's own markers, e.g. `@ExperimentalFoo class Bar`). */
        val classAnnotations: List<String>,
        /** Method name -> the annotation FQNs present on EVERY method of that name (the intersection across
         *  overloads). A marker is thus attributed to a name only when all its overloads carry it — sound: an
         *  opt-in usage is never over-reported for an overload set where only some members are experimental. */
        val methodAnnotations: Map<String, List<String>>,
        /** When THIS class is a `@kotlin.RequiresOptIn` marker annotation, its declared level (`"ERROR"` /
         *  `"WARNING"`, default `"ERROR"`); null when the class is not a marker. */
        val requiresOptInLevel: String?,
    )

    /**
     * Scan [classBytes] for the opt-in mechanism's annotations, or null where this platform has no reader
     * that can (or the bytes are unreadable).
     *
     * A `@RequiresOptIn` marker is `@Retention(BINARY)` and so lands in `RuntimeInvisibleAnnotations`, which
     * is why this reads the bytecode rather than the `@Metadata` blob: the annotations are not reliably in
     * the metadata at all.
     */
    fun scan(classBytes: ByteArray): OptInScan? = scanOptIn(classBytes)
}

internal expect fun scanOptIn(classBytes: ByteArray): KotlinOptIn.OptInScan?
