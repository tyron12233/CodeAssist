package com.tyron.builder.api.internal.tasks.options;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * A representation of a method signature. Contains the method name, erased parameter types and erased return type.
 */
public final class MethodSignature {
    public static MethodSignature from(Method method) {
        return new MethodSignature(method.getName(), MethodType.methodType(
                method.getReturnType(),
                method.getParameterTypes()
        ));
    }

    private final String methodName;
    private final MethodType methodType;

    private MethodSignature(String methodName, MethodType methodType) {
        this.methodName = methodName;
        this.methodType = methodType;
    }

    public String methodName() {
        return methodName;
    }

    public MethodType methodType() {
        return methodType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MethodSignature that = (MethodSignature) o;
        return methodName.equals(that.methodName) && methodType.equals(that.methodType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodName, methodType);
    }
}

