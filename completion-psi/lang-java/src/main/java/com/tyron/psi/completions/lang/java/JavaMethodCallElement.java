package com.tyron.psi.completions.lang.java;

import com.tyron.psi.util.ClassConditionKey;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.psi.PsiCall;

public class JavaMethodCallElement {

    public static final ClassConditionKey<JavaMethodCallElement> CLASS_CONDITION_KEY = ClassConditionKey.create(JavaMethodCallElement.class);
    public static final Key<Boolean> COMPLETION_HINTS = Key.create("completion.hints");

    public static boolean isCompletionMode(@NotNull PsiCall expression) {
        return expression.getUserData(COMPLETION_HINTS) != null;
    }
}
