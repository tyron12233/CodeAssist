package com.tyron.completion.java.util;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.tyron.actions.DataContext;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.ApplicationProvider;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.action.CommonJavaContextKeys;
import com.tyron.completion.java.action.FindCurrentPath;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.tyron.completion.java.parse.CompilationInfo;

import java.io.File;

public class JavaDataContextUtil {

    public static void addEditorKeys(DataContext context, Project project, File file, int cursor) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (project != null && preferences.getBoolean(SharedPreferenceKeys.JAVA_ERROR_HIGHLIGHTING, true)) {
            Module module = project.getModule(file);
            CompilationInfo compilationInfo = module.getUserData(CompilationInfo.COMPILATION_INFO_KEY);
            if (compilationInfo != null) {
                context.putData(CompilationInfo.COMPILATION_INFO_KEY, compilationInfo);
            }
        }
    }
}
