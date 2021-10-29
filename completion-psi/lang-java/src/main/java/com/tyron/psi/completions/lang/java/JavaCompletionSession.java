package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionResultSet;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.lookup.AutoCompletionPolicy;
import com.tyron.psi.lookup.LookupElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JavaCompletionSession {

    private final Set<String> myAddedClasses = new HashSet<>();
    private final Set<String> myKeywords = new HashSet<>();
    private final List<LookupElement> myBatchItems = new ArrayList<>();
    private final CompletionResultSet myResult;

    public JavaCompletionSession(CompletionResultSet result) {
        myResult = result;
    }

    void registerBatchItems(Collection<? extends LookupElement> elements) {
        myBatchItems.addAll(elements);
    }

    void flushBatchItems() {
        myResult.addAllElements(myBatchItems);
        myBatchItems.clear();
    }

    public void addClassItem(LookupElement lookupElement) {
        if (!myResult.getPrefixMatcher().prefixMatches(lookupElement)) return;

        registerClassFrom(lookupElement);
        myResult.addElement(AutoCompletionPolicy.NEVER_AUTOCOMPLETE.applyPolicy(lookupElement));
    }

    void registerClassFrom(LookupElement lookupElement) {
        PsiClass psiClass = extractClass(lookupElement);
        if (psiClass != null) {
            registerClass(psiClass);
        }
    }

    @NotNull
    PrefixMatcher getMatcher() {
        return myResult.getPrefixMatcher();
    }

    @Nullable private static PsiClass extractClass(LookupElement lookupElement) {
        final Object object = lookupElement.getObject();
        if (object instanceof PsiClass) {
            return (PsiClass)object;
        }
        if (object instanceof PsiMethod && ((PsiMethod)object).isConstructor()) {
            return ((PsiMethod)object).getContainingClass();
        }
        return null;
    }

    public void registerClass(@NotNull PsiClass psiClass) {
        ContainerUtil.addIfNotNull(myAddedClasses, getClassName(psiClass));
    }

    @Nullable
    private static String getClassName(@NotNull PsiClass psiClass) {
        String name = psiClass.getQualifiedName();
        return name == null ? psiClass.getName() : name;
    }

    public boolean alreadyProcessed(@NotNull LookupElement element) {
        final PsiClass psiClass = extractClass(element);
        return psiClass != null && alreadyProcessed(psiClass);
    }

    public boolean alreadyProcessed(@NotNull PsiClass object) {
        final String name = getClassName(object);
        return name == null || myAddedClasses.contains(name);
    }

    public boolean isKeywordAlreadyProcessed(@NotNull String keyword) {
        return myKeywords.contains(keyword);
    }

    void registerKeyword(@NotNull String keyword) {
        myKeywords.add(keyword);
    }
}
