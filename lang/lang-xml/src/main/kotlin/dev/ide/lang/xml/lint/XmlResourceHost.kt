package dev.ide.lang.xml.lint

import dev.ide.vfs.VirtualFile

/**
 * A local `@type/name` resource reference the host considers worth resolving (already filtered to local,
 * non-`@+id`-create, non-`?theme` references with a real resource type and name). [rClass] is the R class
 * id (`"string"`, `"drawable"`, …); [name] is the original reference name (for the message); the range is
 * `[start, endExclusive)` over the buffer.
 */
data class XmlResourceRef(val rClass: String, val name: String, val start: Int, val endExclusive: Int)

/**
 * The host data the XML resource diagnostics need but that lives outside lang-xml (the Android resource index /
 * repository and the project's `res/` filesystem). ide-core implements it per project. Keeps lang-xml a
 * generic XML backend - it knows the lint *rules*, the host knows the *resources*. Attribute-schema questions
 * (which attributes/values a tag allows) are a separate seam, [XmlAttributeChecker].
 */
interface XmlResourceHost {
    /** A tag that should carry layout params: a known framework widget or a custom view. */
    fun isViewLike(tag: String): Boolean

    /** Local `@type/name` references in [text] worth resolving (pre-filtered as described on [XmlResourceRef]). */
    fun scanResourceReferences(text: String): List<XmlResourceRef>

    /** Does [file]'s module define ANY resource of [rClass]? When false the type is framework-only/unindexed
     *  and an unresolved reference to it is NOT flagged (avoids false positives while the index is cold). */
    fun typeHasAny(file: VirtualFile, rClass: String): Boolean

    /** Is `@rClass/name` defined for [file]'s module (resource index, falling back to the repository while
     *  the index builds)? */
    fun hasResource(file: VirtualFile, rClass: String, name: String): Boolean

    /** Can [rClass] be created as a value resource (string/color/dimen/bool/integer/id)? */
    fun isValueType(rClass: String): Boolean

    /** Create/append `<rClass name=…>value</rClass>` to the module's `res/values/…` (host filesystem I/O),
     *  de-duplicating the name; returns the (possibly suffixed) name actually written. */
    fun appendValueResource(file: VirtualFile, rClass: String, name: String, value: String): String

    /** Can [rClass] be created as a standalone resource FILE (layout/drawable/menu/anim/…)? */
    fun isFileType(rClass: String): Boolean = false

    /** Create `res/<folder>/<name>.xml` with a minimal stub for [rClass] (host filesystem I/O); returns the
     *  new file's path, or null on failure. Only called when [isFileType] is true. */
    fun createResourceFile(file: VirtualFile, rClass: String, name: String): String? = null
}
