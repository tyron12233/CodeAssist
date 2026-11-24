package dev.codeassist.swift

import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/**
 * What a `.swift` file looks like in the tree, on tabs and in breadcrumbs. The tree asks the engine
 * ([SwiftFileIconProvider]); tabs and breadcrumbs resolve the id in the UI layer, which [SwiftUiPlugin] registers the
 * art for.
 */
object SwiftFileIcons {
    const val SWIFT = "swift.file"
    val SUFFIXES = listOf(".swift")

    fun idFor(fileName: String): String? = if (SUFFIXES.any { fileName.lowercase().endsWith(it) }) SWIFT else null
}

/** The engine half. Above the built-in fallback (0), below android-support (100). */
object SwiftFileIconProvider : FileIconProvider {
    override val priority: Int get() = 50

    override fun iconFor(target: IconTarget): String? = when (target) {
        is IconTarget.File -> SwiftFileIcons.idFor(target.fileName)
        else -> null
    }
}
