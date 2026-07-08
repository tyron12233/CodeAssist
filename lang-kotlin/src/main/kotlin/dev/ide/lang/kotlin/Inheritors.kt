package dev.ide.lang.kotlin

/**
 * A gutter "implementations/overrides" marker: [offset] anchors the type declaration's name identifier, and
 * [targets] are its DIRECT inheritors (subtypes) discovered via the `SubtypeIndex` family. [isInterface]
 * picks the icon the editor draws (IntelliJ uses a different glyph for "is implemented" vs "is subclassed").
 * Locations are NOT resolved here — the gutter only needs the count/kind; a click resolves a target lazily
 * (see [KotlinSourceAnalyzer.declarationLocation]).
 */
data class InheritorMarker(val offset: Int, val isInterface: Boolean, val targets: List<InheritorTarget>)

/** One inheritor of a marked type: its [fqn] and [kind] (`class`/`interface`/`object`/…), for the picker label. */
data class InheritorTarget(val fqn: String, val kind: String)
