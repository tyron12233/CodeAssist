package com.tyron.completion.java.action.quickfix;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.java.action.api.ActionContext;
import com.tyron.completion.java.action.api.ActionProvider;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.util.JCDiagnostic;

public class MigrateTypeFix extends ActionProvider {

    public static final String ERROR_CODE = "compiler.err.prob.found.req";

    @Override
    public boolean isApplicable(ActionContext context, @Nullable String errorCode) {
        return ERROR_CODE.equals(errorCode);
    }

    @Override
    public void addMenus(@NonNull ActionContext context) {
        Diagnostic<? extends JavaFileObject> diagnostic = context.getDiagnostic();
        ClientCodeWrapper.DiagnosticSourceUnwrapper unwrapper = (ClientCodeWrapper.DiagnosticSourceUnwrapper) diagnostic;
        JCDiagnostic jcDiagnostic = unwrapper.d;
        jcDiagnostic.getDiagnosticPosition().getTree();
        context.addMenu("quickFix", "Migrate type to test");
    }
}
