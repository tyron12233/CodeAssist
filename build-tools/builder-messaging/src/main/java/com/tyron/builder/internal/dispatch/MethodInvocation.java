package com.tyron.builder.internal.dispatch;

import com.tyron.builder.util.internal.CollectionUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

public class MethodInvocation {
    private static final Object[] ZERO_ARGS = new Object[0];
    private final Method method;
    private final Object[] arguments;

    public MethodInvocation(Method method, Object[] args) {
        this.method = method;
        arguments = args == null ? ZERO_ARGS : args;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public Method getMethod() {
        return method;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }

        MethodInvocation other = (MethodInvocation) obj;
        if (!method.equals(other.method)) {
            return false;
        }

        return Arrays.equals(arguments, other.arguments);
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }

    @Override
    public String toString() {
        return String.format("[MethodInvocation method: %s(%s)]", method.getName(), CollectionUtils
                .join(", ", arguments));
    }
}