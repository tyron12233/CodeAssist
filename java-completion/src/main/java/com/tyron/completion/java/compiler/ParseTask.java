package com.tyron.completion.java.compiler;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.JavacTask;

public class ParseTask {
    public final JavacTask task;
    public final CompilationUnitTree root;

    public ParseTask(JavacTask task, CompilationUnitTree root) {
        this.task = task;
        this.root = root;
    }
}
