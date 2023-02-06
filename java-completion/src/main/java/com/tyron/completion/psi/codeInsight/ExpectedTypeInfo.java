package com.tyron.completion.psi.codeInsight;

import com.tyron.completion.TailType;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

public interface ExpectedTypeInfo {
  /**
   * Exactly the same type is expected
   */
  int TYPE_STRICTLY = 0;
  /**
   * Type or its subtype is expected
   */
  int TYPE_OR_SUBTYPE = 1;
  /**
   * Type or its supertype is expected
   */
  int TYPE_OR_SUPERTYPE = 2;
  /**
   * Type or its subtype is expected; also must be a supertype of {@link #getDefaultType() default type}
   */
  int TYPE_BETWEEN = 3;
  /**
   * Type must be a functional interface that has the same shape
   */
  int TYPE_SAME_SHAPED = 4;

  @MagicConstant(valuesFromClass = ExpectedTypeInfo.class)
  @interface Type {}

  ExpectedTypeInfo[] EMPTY_ARRAY = new ExpectedTypeInfo[0];

  PsiMethod getCalledMethod();

  @NotNull
  PsiType getType();

  PsiType getDefaultType();

  @Type
  int getKind();

  boolean equals(ExpectedTypeInfo info);

  String toString();

  ExpectedTypeInfo @NotNull [] intersect(@NotNull ExpectedTypeInfo info);

  @NotNull TailType getTailType();
}