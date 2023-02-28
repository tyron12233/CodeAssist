package com.tyron.code.ui.legacyEditor.shortcuts.action;

import com.tyron.code.ui.legacyEditor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.legacyEditor.shortcuts.ShortcutAction;
import com.tyron.code.ui.legacyEditor.shortcuts.ShortcutItem;
import com.tyron.legacyEditor.Caret;
import com.tyron.legacyEditor.Editor;

public class TextInsertAction implements ShortcutAction {

    public static final String KIND = "textInsert";

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(Editor editor, ShortcutItem item) {
        Caret cursor = editor.getCaret();

        // temporary solution
        if (editor instanceof CodeEditorView) {
            ((CodeEditorView) editor).commitText(item.label);
        } else {
            editor.insert(cursor.getStartLine(), cursor.getEndColumn(), item.label);
        }
    }
}
