package com.tyron.code.ui.editor.language;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.editor.Editor;

import java.util.List;

import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;

public abstract class DiagnosticAnalyzeManager<T> extends SimpleAnalyzeManager<T> {

    protected boolean mShouldAnalyzeInBg;

    public abstract void setDiagnostics(Editor editor, List<DiagnosticWrapper> diagnostics);

    public void rerunWithoutBg() {
        mShouldAnalyzeInBg = false;
        super.rerun();
    }

    @Override
    public void rerun() {
        mShouldAnalyzeInBg = true;
        super.rerun();
    }
}
