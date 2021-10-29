package com.tyron.psi.completions.lang.java.daemon.impl.quickfix;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiCatchSection;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiStatement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

public class BringVariableIntoScopeFix {

    public static @Nullable
    PsiElement getContainer(PsiElement unresolvedReference) {
        PsiElement container = PsiTreeUtil.getParentOfType(unresolvedReference, PsiCodeBlock.class, PsiClass.class);
        if (!(container instanceof PsiCodeBlock)) return null;
        while (container.getParent() instanceof PsiStatement || container.getParent() instanceof PsiCatchSection) {
            container = container.getParent();
        }
        return container;
    }
}
