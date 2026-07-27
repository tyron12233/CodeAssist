package dev.ide.lang.kotlin

import dev.ide.vfs.VirtualFile

/** The source go-to navigation actions (see [KotlinSourceAnalyzer.navigationTargets]). */
enum class NavKind { DECLARATION, IMPLEMENTATION, TYPE_DECLARATION, SUPER }

/** A resolved navigation destination in project source: [file] + [offset], plus a [label] for the multi-target
 *  picker and a [kind] hint (a lowercase symbol/declaration kind) for its icon. */
data class NavTarget(val file: VirtualFile, val offset: Int, val label: String, val kind: String)

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
