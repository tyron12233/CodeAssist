package com.tyron.completion.java.diagnostics;

import com.tyron.builder.project.api.Module;
import com.tyron.completion.java.compiler.services.NBLog;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.diagnostics.DiagnosticProvider;

import java.io.File;
import java.util.Collections;
import java.util.List;

import javax.tools.Diagnostic;

public class JavaDiagnosticsProvider implements DiagnosticProvider {
    @Override
    public List<? extends Diagnostic<?>> getDiagnostics(Module module, File file) {
        CompilationInfo compilationInfo = CompilationInfo.get(module.getProject(), file);
        if (compilationInfo == null) {
            return Collections.emptyList();
        }

        return NBLog.instance(compilationInfo.impl.getJavacTask().getContext())
                .getDiagnostics(file.toURI());
    }
}
