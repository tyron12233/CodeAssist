package com.tyron.builder.internal.service;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.reflect.JavaMethod;

import java.lang.reflect.Method;

class ReflectionBasedServiceMethod extends AbstractServiceMethod {
    private final JavaMethod<Object, Object> javaMethod;

    ReflectionBasedServiceMethod(Method target) {
        super(target);
        javaMethod = JavaMethod.of(Object.class, target);
    }

    @Override
    public Object invoke(Object target, Object... args) {
        try {
            return javaMethod.invoke(target, args);
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public String toString() {
        return javaMethod.toString();
    }
}