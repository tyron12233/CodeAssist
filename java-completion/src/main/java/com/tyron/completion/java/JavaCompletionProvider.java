package com.tyron.completion.java;

import com.tyron.completion.legacy.CompletionParameters;
import com.tyron.completion.legacy.CompletionProvider;
import com.tyron.completion.EditorMemory;
import com.tyron.completion.java.provider.JavaKotlincCompletionProvider;
import com.tyron.completion.model.CachedCompletion;
import com.tyron.completion.model.CompletionList;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.command.CommandProcessor;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.project.IndexNotReadyException;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.ReferenceRange;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

import java.io.File;

import io.github.rosemoe.sora.widget.CodeEditor;

@Deprecated
public class JavaCompletionProvider extends CompletionProvider {

    private CachedCompletion mCachedCompletion;

    @SuppressWarnings("ALL")
    public JavaCompletionProvider() {

    }

    @Override
    public boolean accept(File file) {
        return file.isFile() && file.getName().endsWith(".java");
    }

    @Override
    public CompletionList complete(CompletionParameters params) {
        throw new UnsupportedOperationException("This class will be removed");
    }
}
