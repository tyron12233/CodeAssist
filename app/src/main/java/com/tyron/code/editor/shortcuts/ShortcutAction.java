package com.tyron.code.editor.shortcuts;

import io.github.rosemoe.editor.widget.CodeEditor;

public interface ShortcutAction {

    boolean isApplicable(String kind);

    void apply(CodeEditor editor, ShortcutItem item);
}
