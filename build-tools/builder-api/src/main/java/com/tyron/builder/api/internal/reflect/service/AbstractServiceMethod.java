package com.tyron.builder.api.internal.reflect.service;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

abstract class AbstractServiceMethod implements ServiceMethod {
    private final Method method;
    private final Class<?> owner;
    private final String name;
    private final Type[] parameterTypes;
    private final Type serviceType;

    AbstractServiceMethod(Method target) {
        this.method = target;
        this.owner = target.getDeclaringClass();
        this.name = target.getName();
        this.parameterTypes = target.getGenericParameterTypes();
        this.serviceType = target.getGenericReturnType();
    }

    @Override
    public Type getServiceType() {
        return serviceType;
    }

    @Override
    public Type[] getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public Class<?> getOwner() {
        return owner;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Method getMethod() {
        return method;
    }
}