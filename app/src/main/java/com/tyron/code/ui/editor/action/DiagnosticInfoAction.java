package com.tyron.code.ui.editor.action;

import android.app.AlertDialog;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;

import javax.tools.Diagnostic;

import java.util.Locale;

/**
 * An action to display information about the diagnostic in the current cursor
 */
public class DiagnosticInfoAction extends AnAction {

    public static final String ID = "editorDiagnosticInfoAction";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        Diagnostic<?> data = event.getData(CommonDataKeys.DIAGNOSTIC);
        if (data == null) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(com.tyron.completion.java.R.string.menu_diagnostic_info_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Diagnostic<?> diagnostic = e.getRequiredData(CommonDataKeys.DIAGNOSTIC);

        new AlertDialog.Builder(e.getDataContext())
                .setTitle(com.tyron.completion.java.R.string.menu_diagnostic_info_title)
                .setMessage(diagnostic.getMessage(Locale.getDefault()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
