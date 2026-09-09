package dev.ide.ui.editor

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import dev.ide.ui.ext.KeymapHost
import dev.ide.ui.ext.keymapKeyName

/**
 * The editor's bridge from a toolkit key event to the keymap's vocabulary, and the ids of the commands the
 * editor itself provides.
 *
 * The keymap speaks canonical key NAMES, not key codes: it lives in the engine tier, which cannot see a
 * `Key`. This is the one table that knows both, so a shortcut written `primary+alt+L` in a settings file or a
 * plugin manifest finds the L key.
 *
 * Only named commands come through here. The caret keys, the editing keys and text input stay on the
 * editor's own key path: they are the text-input contract rather than commands, they have no action id to
 * bind to, and an IME commit is not a key press at all.
 */

/**
 * The command ids the editor's own shortcuts are bound to.
 *
 * The same ids `EDITOR_KEY_DEFAULTS` binds, spelled as constants so the dispatch and the default table cannot
 * disagree about one. A modifier variant that used to be a branch inside one handler is its own command here
 * (declaration vs implementation, next vs previous diagnostic, line vs block comment), which is what makes
 * each independently rebindable.
 */
object EditorCommands {
    const val REFORMAT = "editor.reformat"
    const val OPTIMIZE_IMPORTS = "editor.optimizeImports"
    const val SAVE = "editor.save"
    const val FIND = "editor.find"
    const val REPLACE = "editor.replace"
    const val GO_TO_LINE = "editor.goToLine"
    const val QUICK_DOC = "editor.quickDoc"
    const val GO_TO_DECLARATION = "editor.goToDeclaration"
    const val GO_TO_IMPLEMENTATION = "editor.goToImplementation"
    const val GO_TO_TYPE_DECLARATION = "editor.goToTypeDeclaration"
    const val GO_TO_SUPER = "editor.goToSuper"
    const val COMPLETE_CODE = "editor.completeCode"
    const val PARAMETER_INFO = "editor.parameterInfo"
    const val RENAME = "editor.rename"
    const val RENAME_ALTERNATE = "editor.renameAlternate"
    const val NEXT_DIAGNOSTIC = "editor.nextDiagnostic"
    const val PREVIOUS_DIAGNOSTIC = "editor.previousDiagnostic"
    const val TOGGLE_COMMENT = "editor.toggleComment"
    const val TOGGLE_BLOCK_COMMENT = "editor.toggleBlockComment"
    const val DUPLICATE_LINE = "editor.duplicateLine"
    const val DELETE_LINE = "editor.deleteLine"
    const val JOIN_LINES = "editor.joinLines"
    const val MOVE_LINE_UP = "editor.moveLineUp"
    const val MOVE_LINE_DOWN = "editor.moveLineDown"
    const val CODE_ACTIONS = "editor.codeActions"
    const val CODE_ACTIONS_ALTERNATE = "editor.codeActionsAlternate"
}

/** Resolve [ev] against the installed keymap, in the editor context. */
internal fun resolveEditorCommand(ev: KeyEvent): KeymapHost.Outcome {
    val name = keymapKeyName(ev.key) ?: return KeymapHost.Outcome.None
    return KeymapHost.resolve(
        key = name,
        ctrl = ev.isCtrlPressed,
        shift = ev.isShiftPressed,
        alt = ev.isAltPressed,
        meta = ev.isMetaPressed,
        inEditor = true,
    )
}
