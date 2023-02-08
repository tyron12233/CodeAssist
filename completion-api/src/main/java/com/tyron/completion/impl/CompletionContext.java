package com.tyron.completion.impl;

import com.tyron.completion.CompletionInitializationContext;
import com.tyron.completion.OffsetMap;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

public class CompletionContext {
    public static final Key<CompletionContext> COMPLETION_CONTEXT_KEY = Key.create("CompletionContext");

    public final PsiFile file;
    private final OffsetMap myOffsetMap;

    public CompletionContext(PsiFile file, final OffsetMap offsetMap){
        this.file = file;
        myOffsetMap = offsetMap;
    }

    public int getStartOffset() {
        return getOffsetMap().getOffset(CompletionInitializationContext.START_OFFSET);
    }

    public OffsetMap getOffsetMap() {
        return myOffsetMap;
    }

}