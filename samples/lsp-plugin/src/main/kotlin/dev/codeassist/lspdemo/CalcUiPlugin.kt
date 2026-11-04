package dev.codeassist.lspdemo

import dev.ide.plugin.ui.EditorLanguage
import dev.ide.plugin.ui.SyntaxStyle
import dev.ide.plugin.ui.UiPlugin
import dev.ide.plugin.ui.UiRegistration

/**
 * The UI facet: Calc's lexical colouring (keywords, numbers, `#` comments), which the editor draws on every
 * keystroke before the server's semantic colours arrive, and wherever the server is unavailable.
 */
class CalcUiPlugin : UiPlugin {
    override val id: String = "dev.codeassist.lspdemo"

    override fun contribute(ui: UiRegistration) {
        ui.editorLanguage(
            EditorLanguage(
                id = CalcPlugin.LANGUAGE,
                suffixes = listOf(CalcPlugin.SUFFIX),
                syntax = SyntaxStyle.C_FAMILY,
                keywords = KEYWORDS.toSet(),
                lineComment = "#",
            )
        )
    }
}
