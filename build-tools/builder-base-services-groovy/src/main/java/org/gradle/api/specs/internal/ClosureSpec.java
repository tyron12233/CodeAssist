package org.gradle.api.specs.internal;

import groovy.lang.Closure;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.gradle.api.specs.Spec;

public class ClosureSpec<T> implements Spec<T> {

    private final Closure<?> closure;

    public ClosureSpec(Closure<?> closure) {
        this.closure = closure;
    }

    @Override
    public boolean isSatisfiedBy(T element) {
        Object value = closure.call(element);
        return (Boolean)InvokerHelper.invokeMethod(value, "asBoolean", null);
    }
}
