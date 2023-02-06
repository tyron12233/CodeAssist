package com.tyron.completion.lookup.impl;

import com.tyron.completion.TailType;
import com.tyron.completion.lookup.LookupElement;
import com.tyron.completion.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.meta.PsiMetaData;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;

public class LookupItemUtil {

    private static final Logger LOG = Logger.getInstance(LookupItemUtil.class);

    /**
     * @deprecated use {@link LookupElementBuilder}
     */
    @Deprecated(forRemoval = true)
    @NotNull
    public static LookupElement objectToLookupItem(Object object) {
        if (object instanceof LookupElement) return (LookupElement)object;
//        if (object instanceof PsiClass) {
//            return JavaClassNameCompletionContributor.createClassLookupItem((PsiClass)object, true);
//        }
//        if (object instanceof PsiMethod) {
//            return new JavaMethodCallElement((PsiMethod)object);
//        }
//        if (object instanceof PsiVariable) {
//            return new VariableLookupItem((PsiVariable)object);
//        }
//        if (object instanceof PsiExpression) {
//            return new ExpressionLookupItem((PsiExpression)object);
//        }
//        if (object instanceof PsiType) {
//            return PsiTypeLookupItem.createLookupItem((PsiType)object, null);
//        }
//        if (object instanceof PsiPackage) {
//            return new PackageLookupItem((PsiPackage)object);
//        }

        String s = null;
        LookupItem item = new LookupItem(object, "");
        if (object instanceof PsiElement) {
            s = PsiUtilCore.getName((PsiElement)object);
        }
        TailType tailType = TailType.NONE;
        if (object instanceof PsiMetaData) {
            s = ((PsiMetaData)object).getName();
        }
        else if (object instanceof String) {
            s = (String)object;
        }
//        else if (object instanceof PresentableLookupValue) {
//            s = ((PresentableLookupValue)object).getPresentation();
//        }

        if (s == null) {
            LOG.error("Null string for object: " + object + " of class " + (object != null ? object.getClass() : null));
        }
        item.setLookupString(s);

        item.setTailType(tailType);

        return item;
    }
}
