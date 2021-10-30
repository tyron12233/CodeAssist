package com.tyron.psi.completions.lang.java.util;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiFileSystemItem;

public class PsiTreeUtilEx {

    /**
     * @return closest leaf (not necessarily a sibling) before the given element
     * which has non-empty range and is neither a whitespace nor a comment
     */
    public static @Nullable
    PsiElement prevCodeLeaf(@NotNull PsiElement element) {
        PsiElement prevLeaf = prevLeaf(element, true);
        while (prevLeaf != null && isNonCodeLeaf(prevLeaf)) prevLeaf = prevLeaf(prevLeaf, true);
        return prevLeaf;
    }

    private static boolean isNonCodeLeaf(PsiElement leaf) {
        return StringUtil.isEmptyOrSpaces(leaf.getText()) || getNonStrictParentOfType(leaf, PsiComment.class) != null;
    }

    public static @Nullable PsiElement prevLeaf(@NotNull PsiElement current) {
        if (current instanceof PsiFileSystemItem) return null;
        PsiElement prevSibling = current.getPrevSibling();
        if (prevSibling != null) return lastChild(prevSibling);
        PsiElement parent = current.getParent();
        if (parent == null || parent instanceof PsiFile) return null;
        return prevLeaf(parent);
    }

    public static @Nullable PsiElement prevLeaf(@NotNull PsiElement element, boolean skipEmptyElements) {
        PsiElement prevLeaf = prevLeaf(element);
        while (skipEmptyElements && prevLeaf != null && prevLeaf.getTextLength() == 0) prevLeaf = prevLeaf(prevLeaf);
        return prevLeaf;
    }

    public static @NotNull PsiElement lastChild(@NotNull PsiElement element) {
        PsiElement lastChild = element.getLastChild();
        if (lastChild != null) return lastChild(lastChild);
        return element;
    }

    public static @NotNull PsiElement firstChild(@NotNull PsiElement element) {
        PsiElement child = element.getFirstChild();
        if (child != null) return firstChild(child);
        return element;
    }

    @SafeVarargs
    @Contract("null, _ -> null")
    public static @Nullable <T extends PsiElement> T getNonStrictParentOfType(@Nullable PsiElement element,
                                                                              @NotNull Class<? extends T>... classes) {
        PsiElement run = element;
        while (run != null) {
            if (instanceOf(run, classes)) {
                //noinspection unchecked
                return (T)run;
            }
            if (run instanceof PsiFile) break;
            run = run.getParent();
        }

        return null;
    }


    public static boolean instanceOf(Object object, @NotNull Class<?>... classes) {
        if (object != null) {
            for (Class<?> c : classes) {
                if (c.isInstance(object)) return true;
            }
        }
        return false;
    }
}
