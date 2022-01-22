package com.tyron.completion.java.patterns;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.patterns.InitialPatternCondition;
import org.openjdk.source.tree.ExpressionTree;

public class ExpressionTreePattern<T extends ExpressionTree, Self extends ExpressionTreePattern<T, Self>> extends JavacTreePattern<T, Self> {
    protected ExpressionTreePattern(@NonNull InitialPatternCondition<T> condition) {
        super(condition);
    }

    protected ExpressionTreePattern(Class<T> aClass) {
        super(aClass);
    }
}
