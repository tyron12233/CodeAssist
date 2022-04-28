package com.tyron.code.ui.editor.shortcuts;

import com.tyron.editor.Editor;

public interface ShortcutAction {

    boolean isApplicable(String kind);

    void apply(Editor editor, ShortcutItem item);
}
