package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.util.AttributeSet;

import com.tyron.actions.DataContext;

import io.github.rosemoe.sora.widget.CodeEditor;

public class CodeEditorView extends CodeEditor implements Editor {
    public CodeEditorView(Context context) {
        super(DataContext.wrap(context));
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        super(DataContext.wrap(context), attrs);
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(DataContext.wrap(context), attrs, defStyleAttr);
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(DataContext.wrap(context), attrs, defStyleAttr, defStyleRes);
    }
}
