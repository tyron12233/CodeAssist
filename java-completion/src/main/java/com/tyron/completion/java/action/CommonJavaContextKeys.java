package com.tyron.completion.java.action;

import com.tyron.completion.java.JavaCompilerService;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.openjdk.source.util.TreePath;

public class CommonJavaContextKeys {

    /**
     * The current TreePath in the editor based on the current cursor
     */
    public static final Key<TreePath> CURRENT_PATH = Key.create("currentPath");

    public static final Key<JavaCompilerService> COMPILER = Key.create("compiler");
}
