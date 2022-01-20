package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.util.AttributeSet;

import com.tyron.actions.DataContext;
import com.tyron.editor.Caret;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

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

    @Override
    public CharPosition getCharPosition(int index) {
        io.github.rosemoe.sora.text.CharPosition charPosition =
                getText().getIndexer().getCharPosition(index);
        return new CharPosition(charPosition.line, charPosition.column);
    }

    @Override
    public int getCharIndex(int line, int column) {
        return getText().getCharIndex(line, column);
    }

    @Override
    public void insert(int line, int column, String string) {
        getText().insert(line, column, string);
    }

    @Override
    public void replace(int line, int column, int endLine, int endColumn, String string) {
        getText().replace(line, column, endLine, endColumn, string);
    }

    @Override
    public void beginBatchEdit() {
        getText().beginBatchEdit();
    }

    @Override
    public void endBatchEdit() {
        getText().endBatchEdit();
    }

    @Override
    public synchronized boolean formatCodeAsync() {
        return CodeEditorView.super.formatCodeAsync();
    }

    @Override
    public synchronized boolean formatCodeAsync(int start, int end) {
        return CodeEditorView.super.formatCodeAsync(start, end);
    }

    @Override
    public Caret getCaret() {
        return new CursorWrapper(getCursor());
    }
}
