package dev.codeassist.dart

import dev.ide.plugin.ui.EditorLanguage
import dev.ide.plugin.ui.EditorPreview
import dev.ide.plugin.ui.FileIcon
import dev.ide.plugin.ui.SyntaxStyle
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The UI facet: how a Dart file looks while it is being typed (keywords, strings, comments, brackets), drawn
 * on every keystroke before the analysis server's semantic colours arrive and wherever it is unavailable,
 * the icon a `.dart` file gets, and the preview pane for a Flutter file's `@Preview` widgets.
 */
class DartUiPlugin : UiPlugin {

    override val id = "dev.codeassist.dart"

    override fun contribute(ui: UiRegistration) {
        ui.editorLanguage(
            EditorLanguage(
                id = DartPlugin.LANGUAGE,
                suffixes = DartFileIcons.SUFFIXES,
                syntax = SyntaxStyle.C_FAMILY,
                keywords = KEYWORDS,
                lineComment = "//",
                blockCommentOpen = BLOCK_OPEN,
                blockCommentClose = BLOCK_CLOSE,
            )
        )
        // Dart's own blue.
        ui.fileIcon(FileIcon(DartFileIcons.DART, DartFileIcons.SUFFIXES, badge = "D", color = 0xFF40C4FF))
        // A Flutter file's @Preview widgets, live and hot-reloading, in the editor's preview pane.
        ui.editorPreview(
            EditorPreview(
                id = "dev.codeassist.dart.flutterPreview",
                title = "Flutter",
                appliesTo = FlutterPreviewFiles::appliesTo,
            ) { ctx -> FlutterPreviewPane(ctx) },
        )
    }

    private companion object {
        // Spelled rather than written literally: a `*` followed by a `/` inside a comment ends it.
        const val BLOCK_OPEN = "/*"
        const val BLOCK_CLOSE = "*" + "/"

        /**
         * Dart's reserved words, built-in identifiers and contextual keywords. Type names (`int`, `String`)
         * are absent on purpose: a capitalized word already colours as a type, and the server colours the
         * rest.
         */
        val KEYWORDS = setOf(
            "abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class", "const",
            "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends",
            "extension", "external", "factory", "false", "final", "finally", "for", "Function", "get", "hide",
            "if", "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new", "null",
            "of", "on", "operator", "part", "required", "rethrow", "return", "sealed", "set", "show", "static",
            "super", "switch", "sync", "this", "throw", "true", "try", "type", "typedef", "var", "void", "when",
            "while", "with", "yield",
        )
    }
}
