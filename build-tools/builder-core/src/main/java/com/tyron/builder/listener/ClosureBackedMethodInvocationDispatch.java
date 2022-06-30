package com.tyron.builder.listener;

import groovy.lang.Closure;
import com.tyron.builder.internal.dispatch.Dispatch;
import com.tyron.builder.internal.dispatch.MethodInvocation;

import java.util.Arrays;

public class ClosureBackedMethodInvocationDispatch implements Dispatch<MethodInvocation> {
    private final String methodName;
    private final Closure closure;

    public ClosureBackedMethodInvocationDispatch(String methodName, Closure closure) {
        this.methodName = methodName;
        this.closure = closure;
    }

    @Override
    public void dispatch(MethodInvocation message) {
        if (message.getMethod().getName().equals(methodName)) {
            Object[] parameters = message.getArguments();
            if (closure.getMaximumNumberOfParameters() < parameters.length) {
                parameters = Arrays.asList(parameters).subList(0, closure.getMaximumNumberOfParameters()).toArray();
            }
            closure.call(parameters);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ClosureBackedMethodInvocationDispatch that = (ClosureBackedMethodInvocationDispatch) o;

        if (!closure.equals(that.closure)) {
            return false;
        }
        if (!methodName.equals(that.methodName)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = methodName.hashCode();
        result = 31 * result + closure.hashCode();
        return result;
    }
}
