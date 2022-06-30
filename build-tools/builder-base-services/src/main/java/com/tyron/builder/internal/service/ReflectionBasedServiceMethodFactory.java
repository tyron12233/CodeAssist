package com.tyron.builder.internal.service;


import java.lang.reflect.Method;

class ReflectionBasedServiceMethodFactory implements ServiceMethodFactory {
    @Override
    public ServiceMethod toServiceMethod(Method method) {
        return new ReflectionBasedServiceMethod(method);
    }
}