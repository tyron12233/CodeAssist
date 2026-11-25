package dev.codeassist.swift

import dev.ide.plugin.ui.EditorLanguage
import dev.ide.plugin.ui.EditorPreview
import dev.ide.plugin.ui.FileIcon
import dev.ide.plugin.ui.SyntaxStyle
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The UI facet: how a Swift file looks while it is being typed (keywords, strings, comments, brackets), the icon a
 * `.swift` file gets, and the SwiftUI preview pane.
 */
class SwiftUiPlugin : UiPlugin {

    override val id = "dev.codeassist.swift"

    override fun contribute(ui: UiRegistration) {
        ui.editorLanguage(
            EditorLanguage(
                id = SwiftPlugin.LANGUAGE,
                suffixes = SwiftFileIcons.SUFFIXES,
                syntax = SyntaxStyle.C_FAMILY,
                keywords = KEYWORDS,
                lineComment = "//",
                blockCommentOpen = BLOCK_OPEN,
                blockCommentClose = BLOCK_CLOSE,
            )
        )
        // Swift's own orange.
        ui.fileIcon(FileIcon(SwiftFileIcons.SWIFT, SwiftFileIcons.SUFFIXES, badge = "S", color = 0xFFF05138))
        // A file's #Preview / PreviewProvider views, rendered by CodeAssist's SwiftUI engine, live and interactive.
        ui.editorPreview(
            EditorPreview(
                id = "dev.codeassist.swift.swiftuiPreview",
                title = "SwiftUI",
                appliesTo = SwiftPreviewFiles::appliesTo,
            ) { ctx -> SwiftPreviewPane(ctx) },
        )
    }

    private companion object {
        // Spelled rather than written literally: a `*` followed by a `/` inside a comment ends it.
        const val BLOCK_OPEN = "/*"
        const val BLOCK_CLOSE = "*" + "/"

        /**
         * Swift's keywords, declaration modifiers and the contextual words that read as keywords. Type names (`Int`,
         * `String`) are absent on purpose: a capitalized word already colours as a type.
         */
        val KEYWORDS = setOf(
            "actor", "any", "as", "associatedtype", "async", "await", "break", "case", "catch", "class", "continue",
            "convenience", "default", "defer", "deinit", "didSet", "do", "dynamic", "else", "enum", "extension",
            "fallthrough", "false", "fileprivate", "final", "for", "func", "get", "guard", "if", "import", "in",
            "indirect", "init", "inout", "internal", "is", "lazy", "let", "macro", "mutating", "nil", "nonisolated",
            "nonmutating", "open", "operator", "optional", "override", "package", "precedencegroup", "private",
            "protocol", "public", "repeat", "required", "rethrows", "return", "self", "Self", "set", "some", "static",
            "struct", "subscript", "super", "switch", "throw", "throws", "true", "try", "typealias", "unowned", "var",
            "weak", "where", "while", "willSet", "consuming", "borrowing", "sending", "isolated",
        )
    }
}
