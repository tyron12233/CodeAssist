package com.tyron.psi.lookup;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderBase;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.ResolveResult;

import java.util.Collections;
import java.util.Set;

public abstract class LookupElement extends UserDataHolderBase {

    public static final LookupElement[] EMPTY_ARRAY = new LookupElement[0];

    @NotNull
    public abstract String getLookupString();

    public Set<String> getAllLookupStrings() {
        return Collections.singleton(getLookupString());
    }

    @NotNull
    public Object getObject() {
        return this;
    }

    @Nullable
    public PsiElement getPsiElement() {
        Object o = getObject();
        if (o instanceof PsiElement) {
            return (PsiElement)o;
        }
        if (o instanceof ResolveResult) {
            return ((ResolveResult)o).getElement();
        }
        return null;
    }

    public boolean isValid() {
        final Object object = getObject();
        if (object instanceof PsiElement) {
            return ((PsiElement)object).isValid();
        }
        return true;
    }

//    public void handleInsert(InsertionContext context) {
//    }

    @Override
    public String toString() {
        return getLookupString();
    }

//    public void renderElement(LookupElementPresentation presentation) {
//        presentation.setItemText(getLookupString());
//    }
//
//    /**
//     * use {@link #as(com.intellij.openapi.util.ClassConditionKey)} instead
//     */
//    @Deprecated
//    @Nullable
//    public final <T> T as(Class<T> aClass) {
//        return as(ClassConditionKey.create(aClass));
//    }
//
//    @Nullable
//    public <T> T as(ClassConditionKey<T> conditionKey) {
//        return conditionKey.isInstance(this) ? (T) this : null;
//    }

    public boolean isCaseSensitive() {
        return true;
    }

    /**
     * Invoked when the completion autopopup contains only the items that exactly match the user-entered prefix to determine
     * whether the popup should be closed to not get in the way when navigating through the code.
     * Should return true if there's some meaningful information in this item's presentation that the user will miss
     * if the autopopup is suddenly closed automatically. Java method parameters are a good example. For simple variables,
     * there's nothing else interesting besides the variable name which is already entered in the editor, so the autopopup may be closed.
     */
//    public boolean isWorthShowingInAutoPopup() {
//        final LookupElementPresentation presentation = new LookupElementPresentation();
//        renderElement(presentation);
//        return !presentation.getTailFragments().isEmpty();
//    }
}
