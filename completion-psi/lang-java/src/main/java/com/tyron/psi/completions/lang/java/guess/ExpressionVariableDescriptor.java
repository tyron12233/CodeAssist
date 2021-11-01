package com.tyron.psi.completions.lang.java.guess;

import com.tyron.psi.completions.lang.java.util.containers.HashingStrategy;
import com.tyron.psi.completions.lang.java.util.psi.JavaPsiEquivalenceUtil;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;

import java.util.Objects;

public class ExpressionVariableDescriptor {
    public static final HashingStrategy<PsiExpression> EXPRESSION_HASHING_STRATEGY = new PsiExpressionStrategy();

    private static class PsiExpressionStrategy implements HashingStrategy<PsiExpression> {
        private static final Logger LOG = Logger.getInstance(PsiExpressionStrategy.class);

        @Override
        public int hashCode(PsiExpression object) {
            if (object == null) {
                return 0;
            }
            else if (object instanceof PsiReferenceExpression) {
                return Objects.hashCode(((PsiReferenceExpression)object).getReferenceName()) * 31 + 1;
            }
            else if (object instanceof PsiMethodCallExpression) {
                return Objects.hashCode(((PsiMethodCallExpression)object).getMethodExpression().getReferenceName()) * 31 + 2;
            }
            return object.getNode().getElementType().hashCode();
        }

        @Override
        public boolean equals(PsiExpression o1, PsiExpression o2) {
            if (o1 == o2) {
                return true;
            }
            if (o1 == null || o2 == null) {
                return false;
            }
            if (JavaPsiEquivalenceUtil.areExpressionsEquivalent(o1, o2)) {
                if (hashCode(o1) != hashCode(o2)) {
                    LOG.error("different hashCodes: " + o1 + "; " + o2 + "; " + hashCode(o1) + "!=" + hashCode(o2));
                }
                return true;
            }
            return false;
        }
    }

}
