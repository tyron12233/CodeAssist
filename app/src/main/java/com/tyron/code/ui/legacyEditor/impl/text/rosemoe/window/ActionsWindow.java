package com.tyron.code.ui.legacyEditor.impl.text.rosemoe.window;

import android.annotation.SuppressLint;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;

@SuppressLint("RestrictedApi")
public class ActionsWindow extends EditorTextActionWindow {

    /**
     * Create a panel for the given editor
     *
     * @param editor Target editor
     */
    public ActionsWindow(CodeEditor editor) {
        super(editor);
    }

    @Override
    public void unregister() {
        super.unregister();
    }
}
