package dev.codeassist.csharp.ui

import dev.codeassist.csharp.CSharpPlugin
import dev.codeassist.csharp.completion.CsKeywords
import dev.codeassist.csharp.project.CsNewFileTemplates
import dev.ide.plugin.ui.EditorLanguage
import dev.ide.plugin.ui.FileIcon
import dev.ide.plugin.ui.SyntaxStyle
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

class CsUiPlugin : UiPlugin {

    // Must match the plugin ID in the manifest.
    override val id = "dev.codeassist.csharp"

    // Editor languages and file icons

    override fun contribute(ui: UiRegistration) {
        ui.editorLanguage(
            EditorLanguage(
                id = CSharpPlugin.LANGUAGE,
                suffixes = CsFileIcons.SUFFIXES,
                syntax = SyntaxStyle.C_FAMILY,
                keywords = CsKeywords.ALL,
                lineComment = "//",
                blockCommentOpen = BLOCK_OPEN,
                blockCommentClose = BLOCK_CLOSE,
                // Directives such as `#nullable enable`, `#region`, and `#if DEBUG` treat the marker and following
                // word as a keyword.
                directivePrefix = "#",
            )
        )

        // Project files use XML syntax, including tags, attributes, comments, and tag auto-closing.
        // Their separate language ID prevents them from being treated as C# source files.
        ui.editorLanguage(
            EditorLanguage(
                id = CSPROJ_LANGUAGE,
                suffixes = listOf(".csproj", ".props", ".targets"),
                syntax = SyntaxStyle.XML,
                blockCommentOpen = "<!--",
                blockCommentClose = "-->",
            )
        )

        ui.fileIcon(
            FileIcon(
                CsFileIcons.CSHARP,
                CsFileIcons.SUFFIXES,
                badge = "C#",
                color = 0xFF9B4F96,
            )
        )

        CsNewFileTemplates.register(ui)
    }

    private companion object {
        const val CSPROJ_LANGUAGE = "csproj"

        // Keep these separate: writing `*/` literally inside a comment would terminate the comment.
        const val BLOCK_OPEN = "/*"
        const val BLOCK_CLOSE = "*" + "/"
    }
}