package com.tyron.completion.java.compiler;

import android.annotation.SuppressLint;
import android.net.Uri;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;


import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import com.sun.source.util.Trees;

public class CompileTask implements AutoCloseable {

    private final CompileBatch mCompileBatch;
    public final JavacTask task;
    public final List<CompilationUnitTree> roots;
    public final List<Diagnostic<? extends JavaFileObject>> diagnostics;

    private final Trees trees;

    public CompileTask(CompileBatch batch) {
        mCompileBatch = batch;
        this.task = batch.task;
        this.trees = Trees.instance(task);

        this.roots = batch.roots;
        this.diagnostics = batch.parent.getDiagnostics();
    }

    public Trees getTrees() {
        return trees;
    }

    public CompilationUnitTree root() {
        if (roots.size() != 1) {
            throw new RuntimeException(Integer.toString(roots.size()));
        }
        return roots.get(0);
    }

    @SuppressLint("NewApi")
    public CompilationUnitTree root(Path file) {
        return root(file.toUri());
    }

    public CompilationUnitTree root(File file) {
        return root(file.toURI());
    }

    public CompilationUnitTree root(JavaFileObject file) {
        return root(file.toUri());
    }

    public CompilationUnitTree root(URI uri) {
        for (CompilationUnitTree root : roots) {
            if (root.getSourceFile().toUri().equals(uri)) {
                return root;
            }
        }
        return null;
    }

    @Override
    public void close() {
        mCompileBatch.close();
    }
}
