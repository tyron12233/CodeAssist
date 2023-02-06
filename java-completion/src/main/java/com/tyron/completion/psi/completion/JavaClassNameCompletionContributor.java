package com.tyron.completion.psi.completion;

import static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns.psiElement;

import com.tyron.completion.InsertHandler;
import com.tyron.completion.psi.completion.item.JavaPsiClassReferenceElement;

import org.jetbrains.kotlin.com.intellij.openapi.util.Condition;
import org.jetbrains.kotlin.com.intellij.patterns.PsiJavaElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifier;
import org.jetbrains.kotlin.com.intellij.util.SmartList;

import java.util.Collections;
import java.util.List;

public class JavaClassNameCompletionContributor {

    public static final PsiJavaElementPattern.Capture<PsiElement> AFTER_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW));


    public static List<JavaPsiClassReferenceElement> createClassLookupItems(final PsiClass psiClass,
                                                                            boolean withInners,
                                                                            InsertHandler<JavaPsiClassReferenceElement> insertHandler,
                                                                            Condition<? super PsiClass> condition) {
        String name = psiClass.getName();
        if (name == null) return Collections.emptyList();
        List<JavaPsiClassReferenceElement> result = new SmartList<>();
        if (condition.value(psiClass)) {
            result.add(AllClassesGetter.createLookupItem(psiClass, insertHandler));
        }

        if (withInners) {
            for (PsiClass inner : psiClass.getInnerClasses()) {
                if (inner.hasModifierProperty(PsiModifier.STATIC)) {
                    for (JavaPsiClassReferenceElement lookupInner : createClassLookupItems(inner, true, insertHandler, condition)) {
                        String forced = lookupInner.getForcedPresentableName();
                        String qualifiedName = name + "." + (forced != null ? forced : inner.getName());
                        lookupInner.setForcedPresentableName(qualifiedName);
                        lookupInner.setLookupString(qualifiedName);
                        result.add(lookupInner);
                    }
                }
            }
        }
        return result;
    }

    public static JavaPsiClassReferenceElement createClassLookupItem(final PsiClass psiClass, final boolean inJavaContext) {
        return AllClassesGetter.createLookupItem(psiClass,  //JAVA_CLASS_INSERT_HANDLER
                AllClassesGetter.TRY_SHORTENING);
    }
}


