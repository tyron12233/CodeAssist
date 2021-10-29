package com.tyron.psi.completions.lang.java.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;

public class GenericsUtilEx {

    /**
     * @param type
     * @return type where "? extends FinalClass" components are replaced with "FinalClass" components.
     */
    public static @NotNull
    PsiType eliminateExtendsFinalWildcard(@NotNull PsiType type) {
        if (!(type instanceof PsiClassType)) return type;
        PsiClassType classType = (PsiClassType)type;
        PsiType[] parameters = classType.getParameters();
        boolean changed = false;
        for (int i = 0; i < parameters.length; i++) {
            PsiType param = parameters[i];
            if (param instanceof PsiWildcardType) {
                PsiWildcardType wildcardType = (PsiWildcardType)param;
                PsiClassType bound = ObjectUtils.tryCast(wildcardType.getBound(), PsiClassType.class);
                if (bound != null && wildcardType.isExtends()) {
                    PsiClass boundClass = PsiUtil.resolveClassInClassTypeOnly(bound);
                    if (boundClass != null && boundClass.hasModifierProperty(PsiModifier.FINAL)) {
                        parameters[i] = bound;
                        changed = true;
                    }
                }
            }
        }
        if (!changed) return type;
        PsiClass target = classType.resolve();
        if (target == null) return classType;
        return JavaPsiFacade.getElementFactory(target.getProject())
                .createType(target, parameters).annotate(classType.getAnnotationProvider());
    }
}
