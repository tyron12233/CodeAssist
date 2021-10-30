package com.tyron.psi.completions.lang.java.guess;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;

import java.util.List;

public abstract class GuessManager {
    public static GuessManager getInstance(Project project) {
        return project.getService(GuessManager.class);
    }

    public abstract PsiType[] guessContainerElementType(PsiExpression containerExpr, TextRange rangeToIgnore);

    public abstract PsiType[] guessTypeToCast(PsiExpression expr);

    @NotNull
    public abstract MultiMap<PsiExpression, PsiType> getControlFlowExpressionTypes(@NotNull PsiExpression forPlace, boolean honorAssignments);

    @NotNull
    public List<PsiType> getControlFlowExpressionTypeConjuncts(@NotNull PsiExpression expr) {
        return getControlFlowExpressionTypeConjuncts(expr, true);
    }

    @NotNull
    public abstract List<PsiType> getControlFlowExpressionTypeConjuncts(@NotNull PsiExpression expr, boolean honorAssignments);
}