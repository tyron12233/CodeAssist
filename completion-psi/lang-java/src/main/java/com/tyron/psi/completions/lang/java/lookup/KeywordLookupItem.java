package com.tyron.psi.completions.lang.java.lookup;

import com.tyron.psi.completions.lang.java.filter.FilterUtil;
import com.tyron.psi.lookup.AutoCompletionPolicy;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementPresentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

/**
 * @author peter
 */
public class KeywordLookupItem extends LookupElement implements TypedLookupItem {
    private final PsiElement myPosition;
    private final PsiKeyword myKeyword;

    public KeywordLookupItem(final PsiKeyword keyword, @NotNull PsiElement position) {
        myKeyword = keyword;
        myPosition = position;
    }

    @NotNull
    @Override
    public Object getObject() {
        return myKeyword;
    }

    @NotNull
    @Override
    public String getLookupString() {
        return myKeyword.getText();
    }

    @Override
    public AutoCompletionPolicy getAutoCompletionPolicy() {
        return AutoCompletionPolicy.GIVE_CHANCE_TO_OVERWRITE;
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
    public void renderElement(LookupElementPresentation presentation) {
        presentation.setItemText(getLookupString());
        presentation.setItemTextBold(true);
    }

    @Override
    public PsiType getType() {
        return FilterUtil.getKeywordItemType(myPosition, getLookupString());
    }
}