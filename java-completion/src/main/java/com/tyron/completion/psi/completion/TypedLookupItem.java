package com.tyron.completion.psi.completion;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

public interface TypedLookupItem {
  @Nullable PsiType getType();
}