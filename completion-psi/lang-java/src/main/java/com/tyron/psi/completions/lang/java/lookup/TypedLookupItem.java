package com.tyron.psi.completions.lang.java.lookup;

import com.tyron.psi.util.ClassConditionKey;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;

/**
 * @author peter
 */
public interface TypedLookupItem {
    ClassConditionKey<TypedLookupItem> CLASS_CONDITION_KEY = ClassConditionKey.create(TypedLookupItem.class);

    @Nullable
    PsiType getType();
}