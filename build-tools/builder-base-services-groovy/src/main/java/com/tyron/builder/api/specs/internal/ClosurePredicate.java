package com.tyron.builder.api.specs.internal;

import groovy.lang.Closure;
import org.codehaus.groovy.runtime.InvokerHelper;

import java.util.function.Predicate;

public class ClosurePredicate<T> implements Predicate<T> {

    private final Closure<?> closure;

    public ClosurePredicate(Closure<?> closure) {
        this.closure = closure;
    }

    @Override
    public boolean test(T element) {
        Object value = closure.call(element);
        return (Boolean)InvokerHelper.invokeMethod(value, "asBoolean", null);
    }
}
