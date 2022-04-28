package com.tyron.code.language;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.editor.Editor;

import java.util.List;

import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.text.ContentReference;

public abstract class DiagnosticAnalyzeManager<T> extends SimpleAnalyzeManager<T> {

    protected boolean mShouldAnalyzeInBg = false;

    public abstract void setDiagnostics(Editor editor, List<DiagnosticWrapper> diagnostics);

    public void rerunWithoutBg() {
        mShouldAnalyzeInBg = false;
        super.rerun();
    }

    public void rerunWithBg() {
        mShouldAnalyzeInBg = true;
        super.rerun();
    }

    @Override
    public void rerun() {
        mShouldAnalyzeInBg = false;
        super.rerun();
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        if (extraArguments == null) {
            extraArguments = new Bundle();
        }
        mShouldAnalyzeInBg = extraArguments.getBoolean("bg", false);
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
}
