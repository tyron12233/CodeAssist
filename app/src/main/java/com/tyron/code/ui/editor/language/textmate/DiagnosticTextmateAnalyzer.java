package com.tyron.code.ui.editor.language.textmate;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.editor.language.DiagnosticSpanMapUpdater;
import com.tyron.editor.Editor;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.textmate.core.grammar.StackElement;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;

public abstract class DiagnosticTextmateAnalyzer extends BaseTextmateAnalyzer {

    protected List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();

    private boolean mShouldAnalyzeInBg;
    private ContentReference ref;

    public DiagnosticTextmateAnalyzer(Editor editor,
                                      String grammarName,
                                      InputStream grammarIns,
                                      Reader languageConfiguration,
                                      IRawTheme theme) throws Exception {
        super(editor, grammarName, grammarIns, languageConfiguration, theme);
    }

    public void setDiagnostics(CodeEditorView codeEditorView, @NonNull List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;
    }
    
    @Override
    public void insert(CharPosition start, CharPosition end, CharSequence insertedText) {
        super.insert(start, end, insertedText);

        if (start.getLine() != end.getLine()) {
            DiagnosticSpanMapUpdater.shiftDiagnosticsOnMultiLineInsert(
                    mDiagnostics,
                    ref,
                    start,
                    end
            );
        } else {
            DiagnosticSpanMapUpdater.shiftDiagnosticsOnSingleLineInsert(
                    mDiagnostics,
                    ref,
                    start,
                    end
            );
        }
    }

    @Override
    public void delete(CharPosition start, CharPosition end, CharSequence deletedText) {
        super.delete(start, end, deletedText);

        if (start.getLine() != end.getLine()) {
            DiagnosticSpanMapUpdater.shiftDiagnosticsOnMultiLineDelete(
                    mDiagnostics,
                    ref,
                    start,
                    end
            );
        } else {
            DiagnosticSpanMapUpdater.shiftDiagnosticsOnSingleLineDelete(
                    mDiagnostics,
                    ref,
                    start,
                    end
            );
        }
    }

    @Override
    public void analyzeCodeBlocks(Content model,
                                  List<CodeBlock> blocks,
                                  Delegate<StackElement> delegate) {
        super.analyzeCodeBlocks(model, blocks, delegate);

        if (getExtraArguments().getBoolean("bg", false)) {
            if (!mShouldAnalyzeInBg) {
                mShouldAnalyzeInBg = true;
                return;
            }
            analyzeInBackground(model);
        }
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        if (extraArguments == null) {
            extraArguments = new Bundle();
        }
        this.ref = content;
        super.reset(content, extraArguments);
    }

    @Override
    public Bundle getExtraArguments() {
        Bundle extraArguments = super.getExtraArguments();
        if (extraArguments == null) {
            extraArguments = new Bundle();
        }
        return extraArguments;
    }

    public abstract void analyzeInBackground(CharSequence contents);

    public void rerunWithoutBg() {
        mShouldAnalyzeInBg = false;
        super.rerun();
    }

    public void rerunWithBg() {
        super.rerun();

        analyzeInBackground(ref);
    }
}
