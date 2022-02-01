package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import io.github.rosemoe.sora2.widget.CodeEditor;

public class RedoAction implements ShortcutAction {

    public static final String KIND = "redoAction";

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(CodeEditor editor, ShortcutItem item) {
        if (editor.canRedo()) {
            editor.redo();
        }
    }
}
