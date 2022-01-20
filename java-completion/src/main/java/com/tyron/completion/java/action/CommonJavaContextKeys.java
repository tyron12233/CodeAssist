package com.tyron.completion.java.action;

import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.JavaCompilerService;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.util.TreePath;

public class CommonJavaContextKeys {

    /**
     * The current TreePath in the editor based on the current cursor
     */
    public static final Key<TreePath> CURRENT_PATH = Key.create("currentPath");

    /**
     * The current diagnostic based on the current cursor
     */
    public static final Key<Diagnostic<?>> DIAGNOSTIC = Key.create("diagnostic");

    public static final Key<JavaCompilerService> COMPILER = Key.create("compiler");
}
