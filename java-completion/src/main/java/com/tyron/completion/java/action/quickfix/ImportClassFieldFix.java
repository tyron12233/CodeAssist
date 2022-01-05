package com.tyron.completion.java.action.quickfix;

import android.app.AlertDialog;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.R;
import com.tyron.completion.java.action.api.Action;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;
import com.tyron.completion.java.rewrite.AddImport;
import com.tyron.completion.java.rewrite.Rewrite;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.util.JCDiagnostic;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

public class ImportClassFieldFix extends ActionProvider {

    public static final String ERROR_CODE = "compiler.err.doesnt.exist";

    @Override
    public boolean isApplicable(ActionContext context, @Nullable String errorCode) {
        return ERROR_CODE.equals(errorCode);
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        Diagnostic<? extends JavaFileObject> diagnostic = context.getDiagnostic();
        if (!(diagnostic instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper)) {
            return;
        }
        JCDiagnostic d = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) diagnostic).d;

        Path file = context.getCurrentFile();
        String title = context.getContext().getString(R.string.import_class_title);
        MenuItem item = context.addMenu("quickFix", title);
        item.setOnMenuItemClickListener(i -> {
            String simpleName= String.valueOf(d.getArgs()[0]);
            boolean isField = simpleName.contains(".");
            String searchName = simpleName;
            if (isField) {
                searchName = searchName.substring(0, searchName.indexOf('.'));
            }

            Map<String, Rewrite> map = new TreeMap<>();
            for (String qualifiedName : context.getCompiler().publicTopLevelTypes()) {
                if (qualifiedName.endsWith("." + searchName)) {
                    if (isField) {
                        qualifiedName = qualifiedName.substring(0,
                                qualifiedName.lastIndexOf('.'));
                        qualifiedName += simpleName;
                    }
                    String name = context.getContext().getString(R.string.import_class_name, qualifiedName);
                    Rewrite addImport = new AddImport(file.toFile(), qualifiedName);
                    map.put(name, addImport);
                }
            }

            String[] titles = map.keySet().toArray(new String[0]);
            new AlertDialog.Builder(context.getContext())
                    .setTitle(R.string.import_class_title)
                    .setItems(titles, (di, w) -> {
                        Rewrite rewrite = map.get(titles[w]);
                        context.performAction(new Action(rewrite));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
    }
}
