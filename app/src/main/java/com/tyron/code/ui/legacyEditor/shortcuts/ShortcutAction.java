package com.tyron.code.ui.legacyEditor.shortcuts;

import com.tyron.editor.Editor;

public interface ShortcutAction {

    boolean isApplicable(String kind);

    void apply(Editor editor, ShortcutItem item);
}
