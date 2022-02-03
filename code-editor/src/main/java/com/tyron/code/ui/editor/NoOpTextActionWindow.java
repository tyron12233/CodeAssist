package com.tyron.code.ui.editor;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;

public class NoOpTextActionWindow extends EditorTextActionWindow {

    /**
     * Create a panel for the given editor
     *
     * @param editor Target editor
     */
    public NoOpTextActionWindow(CodeEditor editor) {
        super(editor);
    }

    @Override
    public void show() {
        // do nothing
    }
}
