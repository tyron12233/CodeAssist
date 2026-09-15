package dev.codeassist.ndk

import dev.ide.plugin.ui.EditorLanguage
import dev.ide.plugin.ui.FileIcon
import dev.ide.plugin.ui.SyntaxStyle
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The UI facet: how a C or C++ file LOOKS while it is being typed.
 *
 * This is not the same layer as diagnostics and it is not optional. Anything driven by the compiler is
 * asynchronous, so without a profile a `.cpp` file opens as grey text and stays that way between keystrokes,
 * with no Toggle Comment, no bracket auto-closing and no smart indent at any point.
 *
 * It also registers what the files LOOK like before they are opened: a file type with no icon is not drawn
 * neutrally, it is drawn as the generic grey document, the one an unrecognised binary gets.
 */
class NdkUiPlugin : UiPlugin {

    override val id = "dev.codeassist.ndk"

    override fun contribute(ui: UiRegistration) {
        ui.editorLanguage(
            EditorLanguage(
                id = NdkPlugin.C_LANGUAGE,
                suffixes = NdkPlugin.C_SUFFIXES,
                syntax = SyntaxStyle.C_FAMILY,
                keywords = C_KEYWORDS,
                lineComment = "//",
                blockCommentOpen = BLOCK_OPEN,
                blockCommentClose = BLOCK_CLOSE,
                directivePrefix = "#",
            )
        )
        ui.editorLanguage(
            EditorLanguage(
                id = NdkPlugin.CPP_LANGUAGE,
                suffixes = NdkPlugin.CPP_SUFFIXES,
                syntax = SyntaxStyle.C_FAMILY,
                keywords = CPP_KEYWORDS,
                lineComment = "//",
                blockCommentOpen = BLOCK_OPEN,
                blockCommentClose = BLOCK_CLOSE,
                // `#include <stdio.h>` reads as a directive and a header name rather than as a run of
                // operators, which is the difference between the first line of a file looking like code and
                // looking like noise.
                directivePrefix = "#",
            )
        )

        // The art AND the name mapping, in one call per file type. The ids are [NdkFileIcons]', which the
        // engine facet's FileIconProvider returns for the project tree, so the tree, the tabs and the
        // breadcrumbs all end up drawing the same badge.
        for ((suffixes, iconId) in NdkFileIcons.SUFFIXES) {
            ui.fileIcon(FileIcon(iconId, suffixes, badge = BADGES.getValue(iconId), color = COLORS.getValue(iconId)))
        }
    }

    private companion object {

        /** One to three characters: longer text is drawn smaller and stops being legible at a tab's size. */
        val BADGES = mapOf(
            NdkFileIcons.CPP to "C++",
            NdkFileIcons.C to "C",
            NdkFileIcons.HEADER to "H",
        )

        /**
         * `0xAARRGGBB`, a blue-violet family that is C's in most editors and is not already spoken for: the
         * IDE's own badges use amber for Java, magenta for Kotlin, green for Gradle and cyan for R8. Three
         * shades rather than three hues, because a header belongs to its source rather than beside it.
         */
        val COLORS = mapOf(
            NdkFileIcons.CPP to 0xFF7C9CE8,
            NdkFileIcons.C to 0xFF5C7CD0,
            NdkFileIcons.HEADER to 0xFF9E8FD8,
        )

        // Spelled rather than written literally: a `*` followed by a `/` inside a Kotlin comment or KDoc
        // ends the comment, and this file has both above.
        const val BLOCK_OPEN = "/*"
        const val BLOCK_CLOSE = "*" + "/"

        /**
         * C keywords, through C23.
         *
         * Type NAMES are deliberately absent: the shared scanner already colors a capitalized word as a type
         * and a word before `(` as a call, so listing `size_t` here would buy nothing and listing `int` is
         * what actually matters.
         */
        val C_KEYWORDS = setOf(
            "alignas", "alignof", "auto", "bool", "break", "case", "char", "const", "constexpr", "continue",
            "default", "do", "double", "else", "enum", "extern", "false", "float", "for", "goto", "if",
            "inline", "int", "long", "nullptr", "register", "restrict", "return", "short", "signed",
            "sizeof", "static", "static_assert", "struct", "switch", "thread_local", "true", "typedef",
            "typeof", "union", "unsigned", "void", "volatile", "while",
            "_Atomic", "_BitInt", "_Complex", "_Generic", "_Imaginary", "_Noreturn",
        )

        /** C++ keywords through C++20, plus the C ones it shares. */
        val CPP_KEYWORDS = C_KEYWORDS + setOf(
            "asm", "catch", "class", "co_await", "co_return", "co_yield", "concept", "const_cast",
            "consteval", "constinit", "decltype", "delete", "dynamic_cast", "explicit", "export", "friend",
            "mutable", "namespace", "new", "noexcept", "operator", "private", "protected", "public",
            "reinterpret_cast", "requires", "static_cast", "template", "this", "throw", "try", "typeid",
            "typename", "using", "virtual", "wchar_t", "char8_t", "char16_t", "char32_t",
            "and", "or", "not", "xor", "bitand", "bitor", "compl", "and_eq", "or_eq", "not_eq", "xor_eq",
        )
    }
}
