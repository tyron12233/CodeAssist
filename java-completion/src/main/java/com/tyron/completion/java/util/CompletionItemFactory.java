package com.tyron.completion.java.util;

import static com.tyron.completion.java.util.ElementUtil.simpleType;

import androidx.annotation.NonNull;

import com.tyron.completion.DefaultInsertHandler;
import com.tyron.completion.java.provider.JavaSortCategory;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionItemWithMatchLevel;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.psi.completion.item.KeywordCompletionItem;
import com.tyron.completion.psi.completion.item.MethodCompletionItem;
import com.tyron.completion.psi.completion.item.SimplePsiCompletionItem;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiField;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

import java.util.Arrays;
import java.util.stream.Collectors;

import javax.lang.model.element.Element;

public class CompletionItemFactory {

    /**
     * Creates a completion item from a java psi element
     * @param element The psi element
     * @return the completion item instance
     */
    public static CompletionItemWithMatchLevel forPsiElement(PsiNamedElement element, PsiElement position) {
        if (element instanceof PsiMethod) {
            return forPsiMethod(((PsiMethod) element), position);
        }
        return item(element, position);
    }

    public static MethodCompletionItem forPsiMethod(PsiMethod method, PsiElement position) {
        return new MethodCompletionItem(method, position);
    }


    public static CompletionItemWithMatchLevel item(PsiNamedElement toInsert, PsiElement position) {
        return new SimplePsiCompletionItem(toInsert, toInsert.getName(), position);
    }


    public static CompletionItem keyword(String keyword) {
        CompletionItem completionItem =
                CompletionItem.create(keyword, keyword, keyword, DrawableKind.Keyword);
        completionItem.setSortText(JavaSortCategory.KEYWORD.toString());
        return completionItem;
    }

    private static DrawableKind getKind(Element element) {
        switch (element.getKind()) {
            case METHOD:
                return DrawableKind.Method;
            case CLASS:
                return DrawableKind.Class;
            case INTERFACE:
                return DrawableKind.Interface;
            case FIELD:
                return DrawableKind.Field;
            default:
                return DrawableKind.LocalVariable;
        }
    }
}
