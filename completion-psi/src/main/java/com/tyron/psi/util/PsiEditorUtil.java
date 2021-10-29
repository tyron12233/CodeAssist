package com.tyron.psi.util;

import com.tyron.psi.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;

public class PsiEditorUtil {

    @NotNull
    public static PsiFile getPsiFile(Editor editor) {
        Project project = editor.getProject();
        assert project != null;
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        assert psiFile != null;
        return psiFile;
    }
}