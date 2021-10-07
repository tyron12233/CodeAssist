package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

public class TextInsertAction implements ShortcutAction {

    @Override
    public boolean isApplicable(String kind) {
        return kind.equals("textinsert");
    }

    @Override
    public void apply(CodeEditor editor, ShortcutItem item) {
        Cursor cursor = editor.getCursor();
        editor.getText().insert(cursor.getLeftLine(), cursor.getLeftColumn(), item.label);
    }
}
