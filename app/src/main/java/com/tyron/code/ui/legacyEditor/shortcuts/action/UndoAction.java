package com.tyron.code.ui.legacyEditor.shortcuts.action;

import com.tyron.code.ui.legacyEditor.shortcuts.ShortcutAction;
import com.tyron.code.ui.legacyEditor.shortcuts.ShortcutItem;
import com.tyron.editor.Editor;

public class UndoAction implements ShortcutAction {

    public static final String KIND = "undoAction";

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(Editor editor, ShortcutItem item) {
        if (editor.getContent().canUndo()) {
            editor.getContent().undo();
        }
    }
}
