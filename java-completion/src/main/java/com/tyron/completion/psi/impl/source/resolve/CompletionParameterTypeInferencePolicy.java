package com.tyron.completion.psi.impl.source.resolve;

import com.tyron.completion.psi.codeInsight.ExpectedTypeInfo;
import com.tyron.completion.psi.codeInsight.ExpectedTypesProvider;

import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.psi.ConstraintType;
import org.jetbrains.kotlin.com.intellij.psi.PsiCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiPrimitiveType;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiWildcardType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.resolve.ProcessCandidateParameterTypeInferencePolicy;

public final class CompletionParameterTypeInferencePolicy extends ProcessCandidateParameterTypeInferencePolicy {
  public static final CompletionParameterTypeInferencePolicy INSTANCE = new CompletionParameterTypeInferencePolicy();

  private CompletionParameterTypeInferencePolicy() {
  }

  @Override
  public PsiType getDefaultExpectedType(PsiCallExpression methodCall) {
    ExpectedTypeInfo expectedType = ExpectedTypesProvider.getSingleExpectedTypeForCompletion(methodCall);
    return expectedType == null ? (PsiPrimitiveType) PsiType.NULL : expectedType.getType();
  }

  @Override
  public Pair<PsiType, ConstraintType> getInferredTypeWithNoConstraint(PsiManager psiManager, PsiType superType) {
    if (!(superType instanceof PsiWildcardType)) {
      return new Pair<>(PsiWildcardType.createExtends(psiManager, superType), ConstraintType.EQUALS);
    }
    else {
      return Pair.create(superType, ConstraintType.SUBTYPE);
    }
  }

  @Override
  public boolean inferRuntimeExceptionForThrownBoundWithNoConstraints() {
    return false;
  }

  @Override
  public PsiType adjustInferredType(PsiManager manager, PsiType guess, ConstraintType constraintType) {
    if (guess != null && !(guess instanceof PsiWildcardType) && guess != PsiType.NULL) {
        if (constraintType == ConstraintType.SUPERTYPE) {
            return PsiWildcardType.createExtends(manager, guess);
        } else if (constraintType == ConstraintType.SUBTYPE) {
            return PsiWildcardType.createSuper(manager, guess);
        }
    }
    return guess;
  }

  @Override
  public boolean isVarargsIgnored() {
    return true;
  }

  @Override
  public boolean inferLowerBoundForFreshVariables() {
    return true;
  }

  @Override
  public boolean requestForBoxingExplicitly() {
    return true;
  }
}