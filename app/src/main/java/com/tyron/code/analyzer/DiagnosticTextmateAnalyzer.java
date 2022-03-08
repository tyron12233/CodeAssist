package com.tyron.code.analyzer;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.language.DiagnosticSpanMapUpdater;
import com.tyron.code.language.HighlightUtil;
import com.tyron.editor.Editor;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.StyleReceiver;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.textmate.core.grammar.StackElement;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;

public abstract class DiagnosticTextmateAnalyzer extends BaseTextmateAnalyzer {

    protected List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();
    private boolean mShouldAnalyzeInBg;
    private ContentReference ref;
    protected Editor mEditor;

    private final Consumer<Styles> mStyleModifier;

    public DiagnosticTextmateAnalyzer(Editor editor,
                                      String grammarName,
                                      InputStream grammarIns,
                                      Reader languageConfiguration,
                                      IRawTheme theme) throws Exception {
        super(editor, grammarName, grammarIns, languageConfiguration, theme);
        mEditor = editor;
        mStyleModifier = this::modifyStyles;
    }

    protected void modifyStyles(Styles styles) {
        if (styles == null) {
            return;
        }
        HighlightUtil.clearDiagnostics(styles);
        HighlightUtil.markDiagnostics(mEditor, mDiagnostics, styles);
    }

    public void setDiagnostics(CodeEditorView codeEditorView,
                               @NonNull List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;
    }

    @Override
    public void setReceiver(@Nullable StyleReceiver receiver) {
        if (receiver != null) {
            super.setReceiver(new StyleReceiverInterceptor(receiver, mStyleModifier));
        } else {
            super.setReceiver(null);
        }
    }

    @Override
    public void insert(CharPosition start, CharPosition end, CharSequence insertedText) {
        super.insert(start, end, insertedText);

        if (getExtraArguments().getBoolean("bg", false)) {
            if (!mShouldAnalyzeInBg) {
                mShouldAnalyzeInBg = true;
            } else {
                analyzeInBackground(ref.getReference());
            }
        }

        if (start.getLine() != end.getLine()) {
            DiagnosticSpanMapUpdater
                    .shiftDiagnosticsOnMultiLineInsert(mDiagnostics, ref, start, end);
        } else {
            DiagnosticSpanMapUpdater
                    .shiftDiagnosticsOnSingleLineInsert(mDiagnostics, ref, start, end);
        }
    }

    @Override
    public void delete(CharPosition start, CharPosition end, CharSequence deletedText) {
        super.delete(start, end, deletedText);

        if (getExtraArguments().getBoolean("bg", false)) {
            if (!mShouldAnalyzeInBg) {
                mShouldAnalyzeInBg = true;
            } else {
                analyzeInBackground(ref.getReference());
            }
        }

        if (start.getLine() != end.getLine()) {
            DiagnosticSpanMapUpdater
                    .shiftDiagnosticsOnMultiLineDelete(mDiagnostics, ref, start, end);
        } else {
            DiagnosticSpanMapUpdater
                    .shiftDiagnosticsOnSingleLineDelete(mDiagnostics, ref, start, end);
        }
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        // it CAN be null.
        //noinspection ConstantConditions
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

    @Override
    public void destroy() {
        mEditor = null;
        super.destroy();
    }

    public abstract void analyzeInBackground(CharSequence contents);

    public void rerunWithoutBg() {
        mShouldAnalyzeInBg = false;
        super.rerun();
    }

    public void rerunWithBg() {
        super.rerun();

        analyzeInBackground(ref.getReference());
    }

    public static class StyleReceiverInterceptor implements StyleReceiver {

        private final StyleReceiver mReceiver;
        private final Consumer<Styles> mConsumer;

        public StyleReceiverInterceptor(@NonNull StyleReceiver base, @NonNull Consumer<Styles> consumer) {
            mReceiver = base;
            mConsumer = consumer;
        }

        @Override
        public void setStyles(AnalyzeManager sourceManager, Styles styles) {
            mConsumer.accept(styles);
            mReceiver.setStyles(sourceManager, styles);
        }
    }
}
