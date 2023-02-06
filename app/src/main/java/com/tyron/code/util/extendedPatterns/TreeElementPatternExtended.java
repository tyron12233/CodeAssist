package com.tyron.code.util.extendedPatterns;

import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.patterns.TreeElementPattern;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public abstract class TreeElementPatternExtended<ParentType, T extends ParentType,
        Self extends TreeElementPatternExtended<ParentType, T, Self>> extends TreeElementPattern<ParentType, T, Self> {

    protected TreeElementPatternExtended(Class<T> aClass) {
        super(aClass);
    }

    public Self inside(final boolean strict, final ElementPattern<? extends ParentType> pattern) {
        return with(new PatternConditionPlus<T, ParentType>("inside", pattern) {
            @Override
            public boolean processValues(T t,
                                         ProcessingContext context,
                                         PairProcessor<ParentType, ProcessingContext> processor) {
                ParentType element = strict ? getParent(t) : t;
                while (element != null) {
                    if (!processor.process(element, context)) return false;
                    element = getParent(element);
                }
                return true;
            }
        });
    }


}
