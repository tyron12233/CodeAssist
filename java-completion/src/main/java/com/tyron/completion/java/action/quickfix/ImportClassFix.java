package com.tyron.completion.java.action.quickfix;

import android.app.AlertDialog;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

public class ImportClassFix extends ActionProvider {

    public static final String ERROR_CODE = "compiler.err.cant.resolve.location";

    @Override
    public boolean isApplicable(@Nullable String errorCode) {
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
        MenuItem item = context.addMenu("quickFix", "Import class");
        item.setOnMenuItemClickListener(i -> {
            String simpleName= String.valueOf(d.getArgs()[1]);

            Map<String, Rewrite> map = new TreeMap<>();
            for (String qualifiedName : context.getCompiler().publicTopLevelTypes()) {
                if (qualifiedName.endsWith("." + simpleName)) {
                    String title = "Import " + qualifiedName;
                    Rewrite addImport = new AddImport(file.toFile(), qualifiedName);
                    map.put(title, addImport);
                }
            }

            if (map.size() == 1) {
                context.performAction(new Action(map.values().iterator().next()));
                return true;
            }

            String[] titles = map.keySet().toArray(new String[0]);
            new AlertDialog.Builder(context.getContext())
                    .setTitle("Import class")
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
