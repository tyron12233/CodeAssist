package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;

import com.tyron.actions.DataContext;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.editor.Caret;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Content;
import com.tyron.editor.Editor;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import io.github.rosemoe.sora.widget.CodeEditor;

public class CodeEditorView extends CodeEditor implements Editor {

    private boolean mIsBackgroundAnalysisEnabled;

    private List<DiagnosticWrapper> mDiagnostics;
    private Consumer<List<DiagnosticWrapper>> mDiagnosticsListener;
    private File mCurrentFile;

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
    public void draw(Canvas canvas) {
        drawView(canvas);
        super.draw(canvas);
    }

    @Override
    public List<DiagnosticWrapper> getDiagnostics() {
        return mDiagnostics;
    }

    @Override
    public void setDiagnostics(List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;

        if (mDiagnosticsListener != null) {
            mDiagnosticsListener.accept(mDiagnostics);
        }
    }

    public void setDiagnosticsListener(Consumer<List<DiagnosticWrapper>> listener) {
        mDiagnosticsListener = listener;
    }

    @Override
    public File getCurrentFile() {
        return mCurrentFile;
    }

    @Override
    public void openFile(File file) {
        mCurrentFile = file;
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
    public void setSelectionRegion(int lineLeft, int columnLeft, int lineRight, int columnRight) {
        CodeEditorView.super.setSelectionRegion(lineLeft, columnLeft, lineRight, columnRight);
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
//        CodeEditorView.super.formatCodeAsync();
//        return CodeEditorView.super.formatCodeAsync(start, end);
        return false;
    }

    @Override
    public Caret getCaret() {
        return new CursorWrapper(getCursor());
    }

    @Override
    public Content getContent() {
        return new ContentWrapper(CodeEditorView.this.getText());
    }

    /**
     * Background analysis can sometimes be expensive.
     * Set whether background analysis should be enabled for this editor.
     */
    public void setBackgroundAnalysisEnabled(boolean enabled) {
        mIsBackgroundAnalysisEnabled = enabled;
    }
}
