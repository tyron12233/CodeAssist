package com.tyron.completion.psi.completion.item;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;

public class KeywordCompletionItem extends SimplePsiCompletionItem {

    public KeywordCompletionItem(PsiKeyword keyword, @NonNull PsiElement position) {
        super(keyword, keyword.getText(), position);

        desc("Keyword");
    }
}
