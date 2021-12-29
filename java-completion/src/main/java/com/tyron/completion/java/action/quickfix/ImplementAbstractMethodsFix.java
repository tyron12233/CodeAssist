package com.tyron.completion.java.action.quickfix;

import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.action.api.Action;
import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;
import com.tyron.completion.java.rewrite.ImplementAbstractMethods;
import com.tyron.completion.java.rewrite.Rewrite;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.util.JCDiagnostic;

import java.util.Collections;
import java.util.List;

public class ImplementAbstractMethodsFix extends ActionProvider {

    public static final String ERROR_CODE = "compiler.err.does.not.override.abstract";

    @Override
    public boolean isApplicable(@Nullable String errorCode) {
       return ERROR_CODE.equals(errorCode);
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        Diagnostic<? extends JavaFileObject> diagnostic = context.getDiagnostic();
        if (diagnostic instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper) {
            JCDiagnostic d = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) diagnostic).d;
            Rewrite rewrite = new ImplementAbstractMethods(d);
            Action action = new Action(rewrite);

            MenuItem item = context.addMenu("quickFix", "Implement abstract methods");
            item.setOnMenuItemClickListener(i -> {
                context.performAction(action);
                return true;
            });
        }
    }
}
