package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import io.github.rosemoe.sora2.text.Cursor;
import io.github.rosemoe.sora2.widget.CodeEditor;

public class TextInsertAction implements ShortcutAction {

    public static final String KIND = "textInsert";

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(CodeEditor editor, ShortcutItem item) {
        Cursor cursor = editor.getCursor();
        editor.getText().insert(cursor.getLeftLine(), cursor.getLeftColumn(), item.label);
    }
}
