package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import io.github.rosemoe.editor.widget.CodeEditor;

public class TextEditAction implements ShortcutAction {

    @Override
    public boolean isApplicable(String kind) {
        return kind.equals("textedit");
    }

    @Override
    public void apply(CodeEditor editor, ShortcutItem item) {

    }
}
