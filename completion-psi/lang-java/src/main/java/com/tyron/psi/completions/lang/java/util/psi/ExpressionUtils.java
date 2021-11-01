package com.tyron.psi.completions.lang.java.util.psi;

import static org.jetbrains.kotlin.com.intellij.util.ObjectUtils.tryCast;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.util.InheritanceUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;

public class ExpressionUtils {


    @Contract("null, _ -> false; _, null -> false")
    public static boolean isReferenceTo(PsiExpression expression, PsiVariable variable) {
        if (variable == null) return false;
        expression = PsiUtil.skipParenthesizedExprDown(expression);
        if (!(expression instanceof PsiReferenceExpression)) return false;
        PsiReferenceExpression ref = (PsiReferenceExpression)expression;
        if ((variable instanceof PsiLocalVariable || variable instanceof PsiParameter) && ref.isQualified()) {
            // Optimization: references to locals and parameters are unqualified
            return false;
        }
        return ref.isReferenceTo(variable);
    }

    /**
     * Returns an effective qualifier for a reference. If qualifier is not specified, then tries to construct it
     * e.g. creating a corresponding {@link PsiThisExpression}.
     *
     * @param ref a reference expression to get an effective qualifier for
     * @return a qualifier or created (non-physical) {@link PsiThisExpression}.
     *         May return null if reference points to local or member of anonymous class referred from inner class
     */
    @Nullable
    public static PsiExpression getEffectiveQualifier(@NotNull PsiReferenceExpression ref) {
        PsiExpression qualifier = ref.getQualifierExpression();
        if (qualifier != null) return qualifier;
        PsiMember member = tryCast(ref.resolve(), PsiMember.class);
        if (member == null) {
            // Reference resolves to non-member: probably variable/parameter/etc.
            return null;
        }
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(ref.getProject());
        PsiClass memberClass = member.getContainingClass();
        if (memberClass != null) {
            if (member.hasModifierProperty(PsiModifier.STATIC)) {
                return factory.createReferenceExpression(memberClass);
            }
            PsiClass containingClass = ClassUtils.getContainingClass(ref);
            if (containingClass == null) {
                containingClass = PsiTreeUtil.getContextOfType(ref, PsiClass.class);
            }
            if (!InheritanceUtil.isInheritorOrSelf(containingClass, memberClass, true)) {
                containingClass = ClassUtils.getContainingClass(containingClass);
                while (containingClass != null && !InheritanceUtil.isInheritorOrSelf(containingClass, memberClass, true)) {
                    containingClass = ClassUtils.getContainingClass(containingClass);
                }
                if (containingClass != null) {
                    String thisQualifier = containingClass.getQualifiedName();
                    if (thisQualifier == null) {
                        if (PsiUtil.isLocalClass(containingClass)) {
                            thisQualifier = containingClass.getName();
                        } else {
                            // Cannot qualify anonymous class
                            return null;
                        }
                    }
                    return factory.createExpressionFromText(thisQualifier + "." + PsiKeyword.THIS, ref);
                }
            }
        }
        return factory.createExpressionFromText(PsiKeyword.THIS, ref);
    }

}
