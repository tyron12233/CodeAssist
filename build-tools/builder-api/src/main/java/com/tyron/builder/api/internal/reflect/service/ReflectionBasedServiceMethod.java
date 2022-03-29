package com.tyron.builder.api.internal.reflect.service;

import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.api.internal.reflect.JavaMethod;

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