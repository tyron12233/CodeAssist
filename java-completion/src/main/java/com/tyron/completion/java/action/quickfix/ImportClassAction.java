package com.tyron.completion.java.action.quickfix;

import android.app.AlertDialog;

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
import com.tyron.completion.java.rewrite.AddImport;
import com.tyron.completion.java.rewrite.JavaRewrite;
import com.tyron.completion.java.util.ActionUtil;
import com.tyron.completion.java.util.DiagnosticUtil;
import com.tyron.editor.Editor;

import javax.tools.Diagnostic;
import com.sun.tools.javac.api.ClientCodeWrapper;
import com.sun.tools.javac.util.JCDiagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ImportClassAction extends AnAction {

    public static final String ID = "javaImportClassFix";

    public static final String ERROR_CODE = "compiler.err.cant.resolve.location";
    public static final String ERROR_CODE_RETURN_TYPE = "compiler.err.cant.resolve";

    @Override
    public void update(@NonNull AnActionEvent event) {
        Presentation presentation = event.getPresentation();
        presentation.setVisible(false);

        if (!ActionPlaces.EDITOR.equals(event.getPlace())) {
            return;
        }

        Diagnostic<?> diagnostic = event.getData(CommonDataKeys.DIAGNOSTIC);
        if (diagnostic == null) {
            return;
        }

        ClientCodeWrapper.DiagnosticSourceUnwrapper diagnosticSourceUnwrapper =
                DiagnosticUtil.getDiagnosticSourceUnwrapper(diagnostic);
        if (diagnosticSourceUnwrapper == null) {
            return;
        }

        if (!ERROR_CODE.equals(diagnostic.getCode())
                && !ERROR_CODE_RETURN_TYPE.equals(diagnostic.getCode())) {
            return;
        }

        JavaCompilerService compiler = event.getData(CommonJavaContextKeys.COMPILER);
        if (compiler == null) {
            return;
        }

        String simpleName = String.valueOf(diagnosticSourceUnwrapper.d.getArgs()[1]);
        List<String> classNames = new ArrayList<>();
        for (String qualifiedName : compiler.publicTopLevelTypes()) {
            if (qualifiedName.endsWith("." + simpleName)) {
                classNames.add(qualifiedName);
            }
        }

        if (classNames.isEmpty()) {
            return;
        }

        if (classNames.size() > 1) {
            presentation.setText(event.getDataContext().getString(R.string.import_class_title));
        } else {
            String format = event.getDataContext().getString(R.string.import_class_name,
                    ActionUtil.getSimpleName(classNames.iterator().next()));
            presentation.setText(format);
        }

        presentation.setEnabled(true);
        presentation.setVisible(true);
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        Diagnostic<?> diagnostic = e.getData(CommonDataKeys.DIAGNOSTIC);
        diagnostic = DiagnosticUtil.getDiagnosticSourceUnwrapper(diagnostic);
        if (diagnostic == null) {
            return;
        }

        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        JCDiagnostic d = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) diagnostic).d;
        String simpleName= String.valueOf(d.getArgs()[1]);
        JavaCompilerService compiler = e.getRequiredData(CommonJavaContextKeys.COMPILER);
        Path file = e.getRequiredData(CommonDataKeys.FILE).toPath();

        Map<String, JavaRewrite> map = new TreeMap<>();
        for (String qualifiedName : compiler.publicTopLevelTypes()) {
            if (qualifiedName.endsWith("." + simpleName)) {
                String title = e.getDataContext().getString(R.string.import_class_name, qualifiedName);
                JavaRewrite addImport = new AddImport(file.toFile(), qualifiedName);
                map.put(title, addImport);
            }
        }

        if (map.size() == 1) {
            RewriteUtil.performRewrite(editor, file.toFile(), compiler,
                    map.values().iterator().next());
        } else {
            String[] titles = map.keySet().toArray(new String[0]);
            new AlertDialog.Builder(e.getDataContext())
                    .setTitle(R.string.import_class_title)
                    .setItems(titles, (di, w) -> {
                        JavaRewrite rewrite = map.get(titles[w]);
                        RewriteUtil.performRewrite(editor, file.toFile(),
                                compiler, rewrite);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }
}
