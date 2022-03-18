package com.tyron.completion.java.patterns.elements;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.Element;

public abstract class JavacElementPatternCondition<T> extends PatternCondition<T> implements JavacElementPattern{
    public JavacElementPatternCondition(@Nullable @NonNls String debugMethodName) {
        super(debugMethodName);
    }

    @Override
    public abstract boolean accepts(@NotNull Element element, ProcessingContext processingContext);
}
