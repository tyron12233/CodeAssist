package com.tyron.completion.java.provider;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import org.openjdk.source.util.TreePath;

public abstract class BaseCompletionProvider {

    private final JavaCompilerService mCompiler;

    public BaseCompletionProvider(JavaCompilerService service) {
        mCompiler = service;
    }

    protected JavaCompilerService getCompiler() {
        return mCompiler;
    }

    public abstract CompletionList complete(CompileTask task, TreePath path, String partial, boolean endsWithParen);
}
