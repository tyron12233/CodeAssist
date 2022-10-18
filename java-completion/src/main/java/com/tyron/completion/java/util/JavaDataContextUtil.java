package com.tyron.completion.java.util;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.sun.tools.javac.util.JCDiagnostic;
import com.tyron.actions.CommonDataKeys;
import com.tyron.actions.DataContext;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.common.SharedPreferenceKeys;

import com.tyron.completion.java.compiler.services.NBLog;
import com.tyron.completion.java.parse.CompilationInfo;

import java.io.File;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public class JavaDataContextUtil {

    public static void addEditorKeys(DataContext context, Project project, File file, int cursor) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (project != null && preferences.getBoolean(SharedPreferenceKeys.JAVA_ERROR_HIGHLIGHTING, true)) {
            CompilationInfo compilationInfo = CompilationInfo.get(project, file);
            if (compilationInfo != null) {
                context.putData(CompilationInfo.COMPILATION_INFO_KEY, compilationInfo);

                List<JCDiagnostic> diagnostics =
                        NBLog.instance(compilationInfo.impl.getJavacTask().getContext())
                                .getDiagnostics(file.toURI());
                if (!diagnostics.isEmpty()) {
                    Diagnostic<? extends JavaFileObject> diagnostic =
                            DiagnosticUtil.getJCDiagnostic(diagnostics, cursor);
                    if (diagnostic != null) {
                        context.putData(CommonDataKeys.DIAGNOSTIC, new DiagnosticWrapper(diagnostic));
                    }
                }
            }
        }
    }
}
