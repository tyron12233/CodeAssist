package com.tyron.completion.psi.completion.item;

import androidx.annotation.NonNull;

import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementPresentation;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;

public class KeywordLookupItem extends LookupElement {

    private final PsiElement myPosition;
    private final PsiKeyword myKeyword;

    public KeywordLookupItem(final PsiKeyword keyword, @NonNull PsiElement position) {
        myKeyword = keyword;
        myPosition = position;
    }

    @NonNull
    @Override
    public Object getObject() {
        return myKeyword;
    }

    @NonNull
    @Override
    public String getLookupString() {
        return myKeyword.getText();
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof KeywordLookupItem && getLookupString().equals(((KeywordLookupItem)o).getLookupString());
    }

    @Override
    public int hashCode() {
        return getLookupString().hashCode();
    }

    @Override
    public void renderElement(@NonNull LookupElementPresentation presentation) {
        presentation.setItemText(getLookupString());
        presentation.setItemTextBold(true);
    }
}
