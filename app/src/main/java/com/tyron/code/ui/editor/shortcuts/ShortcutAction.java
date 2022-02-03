package com.tyron.code.ui.editor.shortcuts;

import io.github.rosemoe.sora2.widget.CodeEditor;

public interface ShortcutAction {

    boolean isApplicable(String kind);

    void apply(CodeEditor editor, ShortcutItem item);
}
