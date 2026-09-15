package dev.codeassist.ndk

import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/**
 * What a C, C++ or header file looks like in the tree, on tabs and in breadcrumbs.
 *
 * A file type with no icon does not get a neutral default: it gets the generic grey document, the same one
 * an unrecognised binary gets, so a project whose sources are all `.cpp` looks like a folder of unknown
 * files. Naming an icon id is not enough on its own either -- an id nothing registered art for resolves to
 * that same fallback -- so the UI facet registers the art and this maps the names onto it.
 *
 * Two registrations are needed and they are not redundant. The project tree asks the ENGINE which icon a
 * file gets ([FileIconProvider], contributed here); a tab or a breadcrumb resolves the name in the UI layer
 * itself, because a file opens from places that have no tree node. [SUFFIXES] is the one table both read, so
 * the two halves cannot drift -- the facets load off one APK on one classloader, so this object is literally
 * the same object to both.
 */
object NdkFileIcons {

    const val CPP = "ndk.cpp"
    const val C = "ndk.c"
    const val HEADER = "ndk.header"

    /**
     * Suffix to icon id, longest-match-irrelevant because the sets are disjoint: `.cc` does not end with
     * `.c`, and `.hpp` does not end with `.h`.
     */
    val SUFFIXES: List<Pair<List<String>, String>> = listOf(
        listOf(".cpp", ".cc", ".cxx", ".c++", ".inl") to CPP,
        listOf(".c") to C,
        listOf(".h", ".hpp", ".hh", ".hxx") to HEADER,
    )

    /** The icon id for [fileName], or null when it is not a file this plugin knows. */
    fun idFor(fileName: String): String? {
        val name = fileName.lowercase()
        return SUFFIXES.firstOrNull { (suffixes, _) -> suffixes.any { name.endsWith(it) } }?.second
    }
}

/**
 * The engine half: what the project tree asks.
 *
 * Priority 50 sits above the built-in fallback (0) and below android-support (100), which is the right
 * place: the built-in rules have nothing to say about `.cpp`, and nothing about a C file should override the
 * manifest or a ProGuard file. Every target this plugin has no opinion on returns null and falls through.
 */
object NdkFileIconProvider : FileIconProvider {
    override val priority: Int get() = 50

    override fun iconFor(target: IconTarget): String? = when (target) {
        is IconTarget.File -> NdkFileIcons.idFor(target.fileName)
        else -> null
    }
}
