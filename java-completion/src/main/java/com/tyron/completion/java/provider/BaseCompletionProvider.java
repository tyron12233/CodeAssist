package com.tyron.completion.java.provider;

import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

import com.sun.source.util.TreePath;

public abstract class BaseCompletionProvider {

    private final JavaCompilerService mCompiler;

    public BaseCompletionProvider(JavaCompilerService service) {
        mCompiler = service;
    }

    protected JavaCompilerService getCompiler() {
        return mCompiler;
    }

    public abstract void complete(CompletionList.Builder builder, CompileTask task, TreePath path
            , String partial, boolean endsWithParen);
}
