package com.tyron.completion.java.patterns.elements;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternConditionPlus;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import javax.lang.model.element.Element;

public abstract class JavacElementPatternConditionPlus<Target, Value> extends PatternConditionPlus<Target, Value> implements JavacElementPattern {

    public JavacElementPatternConditionPlus(@NonNls String methodName, ElementPattern valuePattern) {
        super(methodName, valuePattern);
    }


    @Override
    public boolean accepts(Element element, ProcessingContext context) {
        return processValues(element, context, (value, context1) -> getValuePattern().accepts(value, context1));
    }

    public abstract boolean processValues(Element target, ProcessingContext context,
                                          PairProcessor<Element, ProcessingContext> processor);


}
