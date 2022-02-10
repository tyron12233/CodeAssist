package com.tyron.code.ui.editor.impl.text.rosemoe;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.tyron.actions.DataContext;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.ui.editor.CodeAssistCompletionAdapter;
import com.tyron.code.ui.editor.CodeAssistCompletionWindow;
import com.tyron.code.ui.editor.EditorViewModel;
import com.tyron.code.ui.editor.NoOpTextActionWindow;
import com.tyron.code.ui.editor.language.DiagnosticAnalyzeManager;
import com.tyron.code.ui.editor.scheme.CompiledEditorScheme;
import com.tyron.editor.Caret;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Content;
import com.tyron.editor.Editor;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class CodeEditorView extends CodeEditor implements Editor {

    private final Set<Character> IGNORED_PAIR_ENDS = ImmutableSet.<Character>builder()
            .add(')')
            .add(']')
            .add('"')
            .add('>')
            .add('\'')
            .add(';')
            .build();

    private boolean mIsBackgroundAnalysisEnabled;

    private List<DiagnosticWrapper> mDiagnostics;
    private Consumer<List<DiagnosticWrapper>> mDiagnosticsListener;
    private File mCurrentFile;
    private EditorViewModel mViewModel;

    public CodeEditorView(Context context) {
        this(DataContext.wrap(context), null);
    }

    public CodeEditorView(Context context, AttributeSet attrs) {
        this(DataContext.wrap(context), attrs, 0);
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(DataContext.wrap(context), attrs, defStyleAttr, 0);
    }

    public CodeEditorView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(DataContext.wrap(context), attrs, defStyleAttr, defStyleRes);

        init();
    }

    private void init() {
        CodeAssistCompletionWindow window =
                new CodeAssistCompletionWindow(this);
        window.setAdapter(new CodeAssistCompletionAdapter());
        replaceComponent(EditorAutoCompletion.class, window);
        replaceComponent(EditorTextActionWindow.class, new NoOpTextActionWindow(this));
    }

    @Override
    public void setColorScheme(@NonNull EditorColorScheme colors) {
        super.setColorScheme(colors);
    }

    @Override
    public List<DiagnosticWrapper> getDiagnostics() {
        return mDiagnostics;
    }

    @Override
    public void setDiagnostics(List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;

        AnalyzeManager manager = getEditorLanguage().getAnalyzeManager();
        if (manager instanceof DiagnosticAnalyzeManager) {
            ((DiagnosticAnalyzeManager<?>) manager).setDiagnostics(this, mDiagnostics);
            ((DiagnosticAnalyzeManager<?>) manager).rerunWithoutBg();
        }
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
    public void commitText(CharSequence text) {
        super.commitText(text);
    }

    @Override
    public void commitText(CharSequence text, boolean applyAutoIndent) {
        if (text.length() == 1) {
            char currentChar = getText().charAt(getCursor().getLeft());
            char c = text.charAt(0);
            if (IGNORED_PAIR_ENDS.contains(c) && c == currentChar) {
                // ignored pair end, just move the cursor over the character
                setSelection(getCursor().getLeftLine(), getCursor().getLeftColumn() + 1);
                return;
            }
        }
        super.commitText(text, applyAutoIndent);
    }

    @Override
    public void deleteText() {
        Cursor cursor = getCursor();
        if (!cursor.isSelected()) {
            io.github.rosemoe.sora.text.Content text = getText();
            int startIndex = cursor.getLeft();
            if (startIndex - 1 >= 0) {
                char deleteChar = text.charAt(startIndex - 1);
                char afterChar = text.charAt(startIndex);
                SymbolPairMatch.Replacement replacement = null;

                SymbolPairMatch pairs = getEditorLanguage().getSymbolPairs();
                if (pairs != null) {
                    replacement = pairs.getCompletion(deleteChar);
                }
                if (replacement != null) {
                    if (("" + deleteChar + afterChar + "").equals(replacement.text)) {
                        text.delete(startIndex - 1, startIndex + 1);
                        return;
                    }
                }
            }
        }
        super.deleteText();
    }

    @Override
    public void insertMultilineString(int line, int column, String string) {
        String currentLine = getText().getLineString(line);
        String currentIndent = currentLine.trim().isEmpty()
                ? currentLine
                : currentLine.substring(0, currentLine.indexOf(currentLine.trim()));

        String[] lines = string.split("\\n");
        if (lines.length == 0) {
            getText().insert(line, column, "\n" + currentIndent);
            return;
        }
        String textToInsert = Arrays.stream(lines)
                .map(s -> currentIndent + s)
                .collect(Collectors.joining("\n"))
                .substring(currentIndent.length());

        getText().insert(line, column, textToInsert);
    }

    @Override
    public void delete(int startLine, int startColumn, int endLine, int endColumn) {
        getText().delete(startLine, startColumn, endLine, endColumn);
    }

    @Override
    public void delete(int startIndex, int endIndex) {
        getText().delete(startIndex, endIndex);
    }

    @Override
    public void replace(int line, int column, int endLine, int endColumn, String string) {
        getText().replace(line, column, endLine, endColumn, string);
    }

    @Override
    public void setSelection(int line, int column) {
        super.setSelection(line, column);
    }

    @Override
    public void setSelectionRegion(int lineLeft, int columnLeft, int lineRight, int columnRight) {
        CodeEditorView.super.setSelectionRegion(lineLeft, columnLeft, lineRight, columnRight);
    }

    @Override
    public void setSelectionRegion(int startIndex, int endIndex) {
        CharPosition start = getCharPosition(startIndex);
        CharPosition end = getCharPosition(endIndex);
        CodeEditorView.super.setSelectionRegion(start.getLine(), start.getColumn(),
                end.getLine(), end.getColumn());
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

    @Override
    public void rerunAnalysis() {
        //noinspection ConstantConditions
        if (getEditorLanguage() != null) {
            AnalyzeManager analyzeManager = getEditorLanguage().getAnalyzeManager();
            if (isBackgroundAnalysisEnabled()) {
                analyzeManager.rerun();
            } else {
                if (analyzeManager instanceof DiagnosticAnalyzeManager) {
                    ((DiagnosticAnalyzeManager<?>) analyzeManager).rerunWithoutBg();
                } else {
                    analyzeManager.rerun();
                }
            }
        }
    }

    @Override
    public boolean isBackgroundAnalysisEnabled() {
        return mIsBackgroundAnalysisEnabled;
    }

    public void setAnalyzing(boolean analyzing) {
        if (mViewModel != null) {
            mViewModel.setAnalyzeState(analyzing);
        }
    }

    public void setViewModel(EditorViewModel editorViewModel) {
        mViewModel = editorViewModel;
    }
}
