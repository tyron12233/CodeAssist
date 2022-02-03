package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;

public class TextInsertAction implements ShortcutAction {

    public static final String KIND = "textInsert";

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(Editor editor, ShortcutItem item) {
        Caret cursor = editor.getCaret();
        editor.insert(cursor.getStartLine(), cursor.getEndColumn(), item.label);
    }
}
