package com.tyron.completion.java.compiler;

import android.annotation.SuppressLint;

import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.util.JavacTask;


import java.nio.file.Path;
import java.util.List;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;

public class CompileTask implements AutoCloseable {

    private final CompileBatch mCompileBatch;
    public final JavacTask task;
    public final List<CompilationUnitTree> roots;
    public final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    public CompileTask(CompileBatch batch) {
        mCompileBatch = batch;
        this.task = batch.task;
        this.roots = batch.roots;
        this.diagnostics = batch.parent.getDiagnostics();
    }

    public CompilationUnitTree root() {
        if (roots.size() != 1) {
            throw new RuntimeException(Integer.toString(roots.size()));
        }
        return roots.get(0);
    }

    @SuppressLint("NewApi")
    public CompilationUnitTree root(Path file) {
        for (CompilationUnitTree root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                return root;
            }
        }
        throw new RuntimeException("not found");
    }

    public CompilationUnitTree root(JavaFileObject file) {
        for (CompilationUnitTree root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                return root;
            }
        }
        throw new RuntimeException("not found");
    }

    @Override
    public void close() {
        mCompileBatch.close();
    }
}
