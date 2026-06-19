package dev.ide.lang.resolve

/**
 * Recovers method **parameter names** and **documentation** (javadoc/KDoc) from attached SOURCES — facts the
 * compiled artifacts don't fully carry: Java bytecode strips parameter names (only `p0`/`p1` survive) and
 * neither bytecode nor Kotlin `@Metadata` carries doc comments. Implementations read `-sources.jar`s, the JDK
 * `src.zip`, and the Android platform `sources/` dir.
 *
 * The host injects an implementation so a language backend stays decoupled from the parser used to read the
 * sources (the Java sources are parsed by the JDT backend, Kotlin sources could be parsed by the Kotlin PSI
 * host — both expose the same neutral facts here).
 */
interface SourceDocProvider {
    /** A declared method's editor-facing facts: real parameter [names] (positional) and cleaned [doc] text. */
    data class MethodDoc(val names: List<String>, val doc: String?)

    /**
     * Look up [methodName] declared on [declaringFqn] (use the simple class name for a constructor), preferring
     * the overload whose parameter count is [arity]. Null when no source is attached for the type.
     */
    fun method(declaringFqn: String, methodName: String, arity: Int): MethodDoc?

    /** The type's own doc comment, or null when unavailable. */
    fun classDoc(fqn: String): String? = null

    companion object {
        /** A provider that knows nothing (no sources attached). */
        val NONE: SourceDocProvider = object : SourceDocProvider {
            override fun method(declaringFqn: String, methodName: String, arity: Int): MethodDoc? = null
        }
    }
}
