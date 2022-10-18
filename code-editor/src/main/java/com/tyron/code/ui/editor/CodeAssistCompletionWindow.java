package com.tyron.code.ui.editor;

import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;

public class CodeAssistCompletionWindow extends EditorAutoCompletion {


    private EditorCompletionAdapter adapter;

    /**
     * Create a panel instance for the given editor
     *
     * @param editor Target editor
     */
    public CodeAssistCompletionWindow(CodeEditor editor) {
        super(editor);
    }

    @Override
    public void show() {
        super.show();
    }

    @Override
    public void setAdapter(EditorCompletionAdapter adapter) {
        this.adapter = adapter;
        super.setAdapter(adapter);
    }

    /**
     * Tries to select the position in the completion list.
     * @return whether the select has succeeded
     */
    public boolean trySelect() {
        if (adapter.getCount() <= 0) {
            return false;
        }

        if (getCurrentPosition() == -1) {
            // select the first position
            select(0);
        } else {
            select();
        }

        return true;
    }
}
