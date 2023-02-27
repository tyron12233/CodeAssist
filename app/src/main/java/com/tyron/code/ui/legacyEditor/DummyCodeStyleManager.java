package com.tyron.code.ui.legacyEditor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.lang.ASTNode;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.ChangedRangesInfo;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.Indent;
import org.jetbrains.kotlin.com.intellij.util.IncorrectOperationException;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;

import java.util.Collection;

public class DummyCodeStyleManager extends CodeStyleManager {

    private final Project project;

    public DummyCodeStyleManager(Project project) {
        this.project = project;
    }

    @Override
    public @NonNull Project getProject() {
        return project;
    }

    @Override
    public @NonNull PsiElement reformat(@NonNull PsiElement psiElement) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull PsiElement reformat(@NonNull PsiElement psiElement,
                                                                  boolean b) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PsiElement reformatRange(@NonNull PsiElement psiElement,
                                    int i,
                                    int i1) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public PsiElement reformatRange(@NonNull PsiElement psiElement,
                                    int i,
                                    int i1,
                                    boolean b) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reformatText(@NonNull PsiFile psiFile,
                             int i,
                             int i1) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reformatText(@NonNull PsiFile psiFile,
                             @NonNull Collection<TextRange> collection) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reformatTextWithContext(@NonNull PsiFile psiFile,
                                        @NonNull ChangedRangesInfo changedRangesInfo) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void adjustLineIndent(@NonNull PsiFile psiFile,
                                 TextRange textRange) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int adjustLineIndent(@NonNull PsiFile psiFile,
                                int i) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int adjustLineIndent(@NonNull Document document, int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isLineToBeIndented(@NonNull PsiFile psiFile, int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String getLineIndent(@NonNull PsiFile psiFile,
                                          int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String getLineIndent(@NonNull Document document,
                                                                    int i) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Indent getIndent(String s, FileType fileType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String fillIndent(Indent indent, FileType fileType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Indent zeroIndent() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reformatNewlyAddedElement(@NonNull ASTNode astNode,
                                          @NonNull ASTNode astNode1) throws IncorrectOperationException {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSequentialProcessingAllowed() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void performActionWithFormatterDisabled(Runnable runnable) {
        runnable.run();
    }

    @Override
    public <T extends Throwable> void performActionWithFormatterDisabled(ThrowableRunnable<T> throwableRunnable) throws T {
        throwableRunnable.run();
    }

    @Override
    public <T> T performActionWithFormatterDisabled(Computable<T> computable) {
        return computable.compute();
    }
}
