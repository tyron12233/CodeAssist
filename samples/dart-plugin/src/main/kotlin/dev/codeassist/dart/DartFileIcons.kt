package dev.codeassist.dart

import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/**
 * What a `.dart` file looks like in the tree, on tabs and in breadcrumbs. The tree asks the engine
 * ([DartFileIconProvider]); tabs and breadcrumbs resolve the id in the UI layer, which [DartUiPlugin]
 * registers the art for. Without both, a Dart file is drawn as the generic grey document.
 */
object DartFileIcons {
    const val DART = "dart.file"
    val SUFFIXES = listOf(".dart")

    fun idFor(fileName: String): String? = if (SUFFIXES.any { fileName.lowercase().endsWith(it) }) DART else null
}

/** The engine half. Above the built-in fallback (0), below android-support (100). */
object DartFileIconProvider : FileIconProvider {
    override val priority: Int get() = 50

    override fun iconFor(target: IconTarget): String? = when (target) {
        is IconTarget.File -> DartFileIcons.idFor(target.fileName)
        else -> null
    }
}
