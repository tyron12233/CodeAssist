package com.tyron.psi.lookup;


import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;

/**
 * @author Maxim.Mossienko
 * @deprecated use {@link LookupElementBuilder}
 */
@Deprecated
public interface LookupValueWithPsiElement {
    PsiElement getElement();
}
