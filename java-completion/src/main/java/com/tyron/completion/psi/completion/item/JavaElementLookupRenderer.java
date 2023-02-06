package com.tyron.completion.psi.completion.item;

import com.tyron.completion.java.util.JavaCompletionUtil;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementRenderer;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocCommentOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;

import java.util.List;

public class JavaElementLookupRenderer  {

    public static boolean isToStrikeout(LookupElement item) {
        final List<PsiMethod> allMethods = JavaCompletionUtil.getAllMethods(item);
        if (allMethods != null){
            for (PsiMethod method : allMethods) {
                if (!method.isValid()) { //?
                    return false;
                }
                if (!isDeprecated(method)) {
                    return false;
                }
            }
            return true;
        }
        return isDeprecated(item.getPsiElement());
    }

    public static boolean isDeprecated(@Nullable PsiElement element) {
        return element instanceof PsiDocCommentOwner && ((PsiDocCommentOwner)element).isDeprecated();
    }
}
