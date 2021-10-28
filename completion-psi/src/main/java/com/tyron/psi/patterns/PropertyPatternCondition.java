package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public abstract class PropertyPatternCondition<T,P> extends PatternConditionPlus<T, P> {

    public PropertyPatternCondition(@NonNls String methodName, final ElementPattern propertyPattern) {
        super(methodName, propertyPattern);
    }

    @Override
    public boolean processValues(T t, ProcessingContext context, PairProcessor<? super P, ? super ProcessingContext> processor) {
        return processor.process(getPropertyValue(t), context);
    }

    @Nullable
    public abstract P getPropertyValue(@NotNull Object o);
}