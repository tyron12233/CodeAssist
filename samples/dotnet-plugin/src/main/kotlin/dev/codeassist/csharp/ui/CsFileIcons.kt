package dev.codeassist.csharp.ui

import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/**
 * What a `.cs` file looks like in the tree, on tabs and in breadcrumbs. The tree asks the engine
 * ([CsFileIconProvider]); tabs and breadcrumbs resolve the id in the UI layer, where `CsUiPlugin` registers the
 * art for it. Without both, a C# file is drawn as the generic grey document.
 */
object CsFileIcons {
    /** The icon id the engine hands out for a C# file and the UI layer draws. */
    const val CSHARP = "csharp.file"

    /** The file-name suffixes of C# source. */
    val SUFFIXES = listOf(".cs")

    /** [CSHARP] for a C# file name, null for any other. */
    fun idFor(fileName: String): String? {
        val lowerCased = fileName.lowercase()
        return if (SUFFIXES.any { lowerCased.endsWith(it) }) CSHARP else null
    }
}

/** The engine half. Above the built-in fallback (0), below android-support (100). */
object CsFileIconProvider : FileIconProvider {
    override val priority: Int get() = 50

    override fun iconFor(target: IconTarget): String? = when (target) {
        is IconTarget.File -> CsFileIcons.idFor(target.fileName)
        else -> null
    }
}
