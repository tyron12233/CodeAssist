package com.tyron.psi.completions.lang.java;

import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

public class ArrayMemberAccess {

    @Nullable
    static LookupElement accessFirstElement(PsiElement element, LookupElement item) {
        if (item.getObject() instanceof PsiLocalVariable) {
            final PsiLocalVariable variable = (PsiLocalVariable)item.getObject();
            final PsiType type = variable.getType();
            final PsiExpression expression = variable.getInitializer();
            if (type instanceof PsiArrayType && expression instanceof PsiNewExpression) {
                final PsiNewExpression newExpression = (PsiNewExpression)expression;
                final PsiExpression[] dimensions = newExpression.getArrayDimensions();
                if (dimensions.length == 1 && "1".equals(dimensions[0].getText()) && newExpression.getArrayInitializer() == null) {
                    final String text = variable.getName() + "[0]";
                    return LookupElementBuilder.create(text);
                   // return new ExpressionLookupItem(createExpression(text, element), variable.getIcon(Iconable.ICON_FLAG_VISIBILITY), text, text);
                }
            }
        }
        return null;
    }
}
