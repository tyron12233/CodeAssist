package com.tyron.psi.completions.lang.java.filter;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

/**
 * @author Dmitry Avdeev
 */
public final class FilterPositionUtil {
    @Nullable
    public static PsiElement searchNonSpaceNonCommentBack(PsiElement element) {
        return element == null ? null : PsiTreeUtil.prevLeaf(element);
    }
}