package com.tyron.code.ui.legacyEditor.shortcuts.action;

import com.tyron.code.ui.legacyEditor.shortcuts.ShortcutAction;
import com.tyron.code.ui.legacyEditor.shortcuts.ShortcutItem;
import com.tyron.legacyEditor.Editor;

public class TextEditAction implements ShortcutAction {

    @Override
    public boolean isApplicable(String kind) {
        return kind.equals("textedit");
    }

    @Override
    public void apply(Editor editor, ShortcutItem item) {

    }
}
