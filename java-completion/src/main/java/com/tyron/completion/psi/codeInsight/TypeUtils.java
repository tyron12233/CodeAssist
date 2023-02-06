package com.tyron.completion.psi.codeInsight;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.CommonClassNames;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFactory;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;

public class TypeUtils {


    public static PsiClassType getType(@NotNull String fqName, @NotNull PsiElement context) {
        final Project project = context.getProject();
        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final GlobalSearchScope scope = context.getResolveScope();
        return factory.createTypeByFQClassName(fqName, scope);
    }

    public static PsiClassType getType(@NotNull PsiClass aClass) {
        return JavaPsiFacade.getElementFactory(aClass.getProject()).createType(aClass);
    }

    public static PsiClassType getObjectType(@NotNull PsiElement context) {
        return getType(CommonClassNames.JAVA_LANG_OBJECT, context);
    }

    public static PsiClassType getStringType(@NotNull PsiElement context) {
        return getType(CommonClassNames.JAVA_LANG_STRING, context);
    }
}
