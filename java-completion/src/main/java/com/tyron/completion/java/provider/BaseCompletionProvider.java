package com.tyron.completion.java.provider;

import com.sun.source.util.TreePath;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.model.CompletionList;

public abstract class BaseCompletionProvider {

    private final JavaCompilerService mCompiler;

    public BaseCompletionProvider(JavaCompilerService service) {
        mCompiler = service;
    }

    protected JavaCompilerService getCompiler() {
        return mCompiler;
    }

    public abstract void complete(CompletionList.Builder builder,
                                  JavacUtilitiesProvider task,
                                  TreePath path,
                                  String partial,
                                  boolean endsWithParen);
}
