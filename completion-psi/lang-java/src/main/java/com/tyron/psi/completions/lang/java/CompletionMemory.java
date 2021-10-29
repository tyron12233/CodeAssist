package com.tyron.psi.completions.lang.java;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.PsiCall;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.SmartPsiElementPointer;

import java.util.LinkedList;
import java.util.Objects;

public class CompletionMemory {

    private static Key<LinkedList<RangeMarker>> LAST_CHOSEN_METHODS = Key.create("COMPLETED_METHODS");
    private static Key<SmartPsiElementPointer<PsiMethod>> CHOSEN_METHODS = Key.create("CHOSEN_METHODS");

    private static TextRange getAnchorRange(PsiCall call) {
        if (call instanceof PsiMethodCallExpression) {
            return ((PsiMethodCallExpression) call).getMethodExpression()
                    .getReferenceNameElement().getTextRange();
        }
        if (call instanceof PsiNewExpression) {
            return ((PsiNewExpression) call).getClassOrAnonymousClassReference()
                    .getReferenceNameElement().getTextRange();
        }
        return null;
    }
    @Nullable
    public static PsiMethod getChosenMethod(PsiCall call) {
        TextRange range = getAnchorRange(call);
        if (range == null) {
            return null;
        }
        LinkedList<RangeMarker> completedMethods = Objects.requireNonNull(call.getContainingFile().getViewProvider()
                .getDocument())
                .getUserData(LAST_CHOSEN_METHODS);

       if (completedMethods == null) {
           return null;
       }

        for (RangeMarker it : completedMethods) {
            if (it.getStartOffset() == range.getStartOffset() && it.getEndOffset() == range.getEndOffset()) {
                return it.getUserData(CHOSEN_METHODS).getElement();
            }
        }

        return null;
    }
}
