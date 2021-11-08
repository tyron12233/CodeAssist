package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import io.github.rosemoe.sora.widget.CodeEditor;

public class UndoAction implements ShortcutAction {

    public static final String KIND = "undoAction";

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(CodeEditor editor, ShortcutItem item) {
        if (editor.canUndo()) {
            editor.undo();
        }
    }
}
