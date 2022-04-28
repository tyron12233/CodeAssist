package org.jetbrains.kotlin.com.intellij.codeInsight;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

import java.util.Arrays;
import java.util.Collection;

public abstract class FileModificationService2 {
    public static FileModificationService2 getInstance() {
        return ApplicationManager.getApplication().getService(FileModificationService2.class);
    }

    public abstract boolean preparePsiElementsForWrite(@NonNull Collection<? extends PsiElement> elements);
    public abstract boolean prepareFileForWrite(@Nullable final PsiFile psiFile);

    public boolean preparePsiElementForWrite(@Nullable PsiElement element) {
        PsiFile file = element == null ? null : element.getContainingFile();
        return prepareFileForWrite(file);
    }

    public boolean preparePsiElementsForWrite(PsiElement... elements) {
        return preparePsiElementsForWrite(Arrays.asList(elements));
    }

    public abstract boolean prepareVirtualFilesForWrite(@NonNull Project project, @NonNull Collection<? extends VirtualFile> files);
}
