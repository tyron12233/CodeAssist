package com.tyron.completion.java.action.quickfix;

import androidx.annotation.NonNull;

import com.tyron.actions.ActionPlaces;
import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.Presentation;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.R;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.util.RewriteUtil;
import com.tyron.completion.java.rewrite.ImplementAbstractMethods;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.editor.Editor;

import javax.tools.Diagnostic;
import com.sun.tools.javac.api.ClientCodeWrapper;
import com.sun.tools.javac.util.JCDiagnostic;

import java.io.File;

public class ImplementAbstractMethodsFix extends AnAction {

    public static final String ID = "javaImplementAbstractMethodsFix";

    public static final String ERROR_CODE = "compiler.err.does.not.override.abstract";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        Diagnostic<?> diagnostic = event.getData(CommonDataKeys.DIAGNOSTIC);
        if (diagnostic == null) {
            return;
        }

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

            ClientCodeWrapper.DiagnosticSourceUnwrapper diagnosticSourceUnwrapper = DiagnosticUtil.getDiagnosticSourceUnwrapper(diagnostic);
        if (diagnosticSourceUnwrapper == null) {
            return;
        }

        if (!ERROR_CODE.equals(diagnostic.getCode())) {
            return;
        }

        JavaCompilerService compiler = event.getData(CommonJavaContextKeys.COMPILER);
        if (compiler == null) {
            return;
        }

        presentation.setVisible(true);
        presentation.setText(event.getDataContext().getString(R.string.menu_quickfix_implement_abstract_methods_title));
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        File file = e.getData(CommonDataKeys.FILE);
        JavaCompilerService compiler = e.getData(CommonJavaContextKeys.COMPILER);
        Diagnostic<?> diagnostic = e.getData(CommonDataKeys.DIAGNOSTIC);
        ClientCodeWrapper.DiagnosticSourceUnwrapper diagnosticSourceUnwrapper =
                DiagnosticUtil.getDiagnosticSourceUnwrapper(diagnostic);
        if (diagnosticSourceUnwrapper == null) {
            return;
        }
        JCDiagnostic jcDiagnostic = diagnosticSourceUnwrapper.d;
        JavaRewrite rewrite = new ImplementAbstractMethods(jcDiagnostic);
        RewriteUtil.performRewrite(editor, file, compiler, rewrite);
    }
}
