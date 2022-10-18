package com.tyron.completion.java.action.quickfix;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.editor.Editor;

import javax.tools.Diagnostic;
import com.sun.source.util.TreePath;

public abstract class ExceptionsQuickFix extends AnAction {

    public static final String ERROR_CODE = "compiler.err.unreported.exception.need.to.catch.or" +
            ".throw";

    @Override
    public void update(@NonNull AnActionEvent event) {
        event.getPresentation().setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        Diagnostic<?> diagnostic = event.getData(CommonDataKeys.DIAGNOSTIC);
        if (diagnostic == null) {
            return;
        }

        if (!ERROR_CODE.equals(diagnostic.getCode())) {
            return;
        }

        TreePath currentPath = event.getData(CommonJavaContextKeys.CURRENT_PATH);
        if (currentPath == null) {
            return;
        }

        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }

        event.getPresentation().setVisible(true);
    }

    @Override
    public abstract void actionPerformed(@NonNull AnActionEvent e);
}
