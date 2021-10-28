package com.tyron.psi.patterns;

import org.jetbrains.annotations.NonNls;

import java.util.Collection;

/**
 * @author peter
 */
public abstract class ValuePatternCondition<T> extends PatternCondition<T>{

    protected ValuePatternCondition(@NonNls String methodName) {
        super(methodName);
    }

    public abstract Collection<T> getValues();
}