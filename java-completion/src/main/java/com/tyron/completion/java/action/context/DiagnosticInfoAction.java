package com.tyron.completion.java.action.context;

import android.app.AlertDialog;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.R;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;

import java.util.Locale;

public class DiagnosticInfoAction extends ActionProvider {

    @Override
    public boolean isApplicable(ActionContext context, @Nullable String errorCode) {
        return true;
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        Diagnostic<? extends JavaFileObject> diagnostic = context.getDiagnostic();
        if (diagnostic == null) {
            return;
        }
        String title = context.getContext().getString(R.string.menu_diagnostic_info_title);
        context.addMenu("context", title).setOnMenuItemClickListener(item -> {
            new AlertDialog.Builder(context.getContext())
                    .setTitle(title)
                    .setMessage(diagnostic.getMessage(Locale.getDefault()))
                    .setPositiveButton(R.string.menu_close, null)
                    .show();
            return true;
        });
    }
}
