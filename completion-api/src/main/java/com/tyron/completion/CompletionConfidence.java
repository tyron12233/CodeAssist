package com.tyron.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.util.ThreeState;

/**
 * Allows skipping completion autopopup according to current context.
 */
public abstract class CompletionConfidence {

  /**
   * Invoked first when a completion autopopup is scheduled. Extensions are able to cancel this completion process based on location.
   * For example, in string literals or comments, completion autopopup may do more harm than good.
   */
  @NotNull
  public ThreeState shouldSkipAutopopup(@NotNull PsiElement contextElement, @NotNull PsiFile psiFile, int offset) {
    return ThreeState.UNSURE;
  }
}