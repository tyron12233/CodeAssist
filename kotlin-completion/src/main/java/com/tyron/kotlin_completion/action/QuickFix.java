package com.tyron.kotlin_completion.action;

import android.content.Context;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.kotlin_completion.CompiledFile;

import javax.tools.Diagnostic;

public abstract class QuickFix extends AnAction {

    public abstract boolean accept(@NonNull String errorCode);

    @CallSuper
    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        CompiledFile compiledFile = event.getData(CommonKotlinKeys.COMPILED_FILE);
        if (compiledFile == null) {
            return;
        }

        Diagnostic<?> diagnostic = event.getData(CommonDataKeys.DIAGNOSTIC);
        if (diagnostic == null) {
            return;
        }

        if (!accept(diagnostic.getCode())) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(getTitle(event.getDataContext()));
    }

    public abstract String getTitle(Context context);
}
