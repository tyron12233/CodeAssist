package com.tyron.psi.patterns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.pom.PomTarget;
import org.jetbrains.kotlin.com.intellij.pom.PomTargetPsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * Factory for {@link PsiElement}, {@link PomTarget}, {@link IElementType} and {@link VirtualFile}-based patterns.
 *
 * @author peter
 */
public class PlatformPatterns extends StandardPatterns {

    public static PsiElementPattern.Capture<PsiElement> psiElement() {
        return new PsiElementPattern.Capture<>(PsiElement.class);
    }

    public static PsiElementPattern.Capture<PsiComment> psiComment() {
        return new PsiElementPattern.Capture<>(PsiComment.class);
    }

    public static PsiElementPattern.Capture<PomTargetPsiElement> pomElement(final ElementPattern<? extends PomTarget> targetPattern) {
        return new PsiElementPattern.Capture<>(PomTargetPsiElement.class).with(new PatternCondition<PomTargetPsiElement>("withPomTarget") {
            @Override
            public boolean accepts(@NotNull final PomTargetPsiElement element, final ProcessingContext context) {
                return targetPattern.accepts(element, context);
            }
        });
    }

    public static PsiFilePattern.Capture<PsiFile> psiFile() {
        return new PsiFilePattern.Capture<>(PsiFile.class);
    }

    public static <T extends PsiFile> PsiFilePattern.Capture<T> psiFile(Class<T> fileClass) {
        return new PsiFilePattern.Capture<>(fileClass);
    }

    public static PsiElementPattern.Capture<PsiElement> psiElement(IElementType type) {
        return psiElement().withElementType(type);
    }

    public static <T extends PsiElement> PsiElementPattern.Capture<T> psiElement(final Class<T> aClass) {
        return new PsiElementPattern.Capture<>(aClass);
    }

    public static IElementTypePattern elementType() {
        return new IElementTypePattern();
    }

    public static VirtualFilePattern virtualFile() {
        return new VirtualFilePattern();
    }
}