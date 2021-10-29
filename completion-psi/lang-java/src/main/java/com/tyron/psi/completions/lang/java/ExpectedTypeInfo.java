package com.tyron.psi.completions.lang.java;

import com.tyron.psi.tailtype.TailType;

import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

/**
 * @author ven
 */
public interface ExpectedTypeInfo {
    int TYPE_STRICTLY = 0;
    int TYPE_OR_SUBTYPE = 1;
    int TYPE_OR_SUPERTYPE = 2;
    int TYPE_BETWEEN = 3;
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

    ExpectedTypeInfo [] intersect(@NotNull ExpectedTypeInfo info);

    @NotNull
    TailType getTailType();
}
