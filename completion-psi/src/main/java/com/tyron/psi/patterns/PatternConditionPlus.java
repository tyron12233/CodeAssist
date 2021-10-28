package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author Gregory.Shrago
 */
public abstract class PatternConditionPlus<Target, Value> extends PatternCondition<Target> implements PairProcessor<Value, ProcessingContext> {
    private final ElementPattern myValuePattern;

    public PatternConditionPlus(@NonNls String methodName, final ElementPattern valuePattern) {
        super(methodName);
        myValuePattern = valuePattern;
    }

    public ElementPattern getValuePattern() {
        return myValuePattern;
    }

    public abstract boolean processValues(final Target t, final ProcessingContext context, final PairProcessor<? super Value, ? super ProcessingContext> processor);

    @Override
    public boolean accepts(@NotNull final Target t, final ProcessingContext context) {
        return !processValues(t, context, this);
    }

    @Override
    public final boolean process(Value p, ProcessingContext context) {
        return !myValuePattern.accepts(p, context);
    }
}