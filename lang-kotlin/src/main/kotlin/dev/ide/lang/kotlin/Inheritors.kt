package dev.ide.lang.kotlin

import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile

/** The source go-to navigation actions (see [KotlinSourceAnalyzer.navigationTargets]). */
enum class NavKind { DECLARATION, IMPLEMENTATION, TYPE_DECLARATION, SUPER }

/** A resolved navigation destination: a project-source [file] + [offset], OR a compiled LIBRARY class (a
 *  [LibraryFile] with a [libraryPath]; the host opens it read-only, fetching decompiled/attached-source text
 *  by FQN). Plus a [label] for the multi-target picker and a [kind] hint (a lowercase symbol/declaration kind,
 *  or `"library"`) for its icon. */
data class NavTarget(val file: VirtualFile, val offset: Int, val label: String, val kind: String)

/** The synthetic path a [NavTarget] carries for a compiled LIBRARY class with no project source: the host
 *  opens it in a read-only tab whose text is fetched (attached source, else decompiled) lazily by FQN. Form:
 *  `library://<fqn>` or `library://<fqn>#<member>` (the member simple name to place the caret on). */
fun libraryPath(fqn: String, member: String? = null): String =
    "library://$fqn" + (member?.let { "#$it" } ?: "")

/** A minimal read-only [VirtualFile] standing in for a compiled library class in a [NavTarget] — it carries
 *  only its synthetic [libraryPath]; the display text is produced on open, never read from disk. */
class LibraryFile(override val path: String) : VirtualFile {
    override val name: String get() = path.substringAfterLast('/').substringBefore('#')
    override val isDirectory: Boolean get() = false
    override val exists: Boolean get() = true
    override val length: Long get() = 0
    override fun parent(): VirtualFile? = null
    override fun children(): List<VirtualFile> = emptyList()
    override fun contentHash(): ContentHash = ContentHash("")
    override fun readBytes(): ByteArray = ByteArray(0)
    override fun readText(): CharSequence = ""
}

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
