package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement;

/**
 * @author peter
 */
public class PsiNamePatternCondition<T extends PsiElement> extends PropertyPatternCondition<T, String> {

    public PsiNamePatternCondition(@NonNls String methodName, final ElementPattern<String> namePattern) {
        super(methodName, namePattern);
    }

    public ElementPattern<String> getNamePattern() {
        return getValuePattern();
    }

    @Override
    public String getPropertyValue(@NotNull final Object o) {
        return o instanceof PsiNamedElement ? ((PsiNamedElement)o).getName() : null;
    }

}