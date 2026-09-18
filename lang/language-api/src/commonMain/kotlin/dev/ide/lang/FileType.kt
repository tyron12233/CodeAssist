package dev.ide.lang

import dev.ide.platform.ExtensionPoint

/**
 * Maps a file (by name suffix) to the [LanguageId] the host should treat it as, so file→language routing is a
 * contribution rather than a hardcoded dispatch in the host. Adding a language's file association is a
 * registration on [FILE_TYPE_EP], like every other capability.
 *
 * A mapping may target a [LanguageId] that has **no** registered [LanguageBackend] (e.g. ProGuard keep-rule
 * files, Markdown): such a file is then edited as plain text and, because the analysis pipeline dispatches by
 * language, is never analysed by another backend (in particular never mis-parsed as Java). When several
 * mappings match, the lowest [order] wins.
 */
class FileTypeMapping(
    val suffixes: List<String>,
    val language: LanguageId,
    val order: Int = 1000,
) {
    fun matches(fileName: String): Boolean = suffixes.any { fileName.endsWith(it) }
}

/** Plugins contribute file-name → [LanguageId] mappings here; the host resolves a file's language against them. */
val FILE_TYPE_EP = ExtensionPoint<FileTypeMapping>("platform.fileType")
