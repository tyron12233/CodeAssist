package com.tyron.psi.completions.lang.java.util.psi;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnonymousClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

public class ClassUtils {

    /**
     * @return containing class for {@code element} ignoring {@link PsiAnonymousClass} if {@code element} is located in corresponding expression list
     */
    @Nullable
    public static PsiClass getContainingClass(PsiElement element) {
        PsiClass currentClass;
        while (true) {
            currentClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (currentClass instanceof PsiAnonymousClass &&
                    PsiTreeUtil.isAncestor(((PsiAnonymousClass)currentClass).getArgumentList(), element, false)) {
                element = currentClass;
            } else {
                return currentClass;
            }
        }
    }
}
