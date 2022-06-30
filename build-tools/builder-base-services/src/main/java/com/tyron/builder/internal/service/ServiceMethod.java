package com.tyron.builder.internal.service;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

public interface ServiceMethod {
    Class<?> getOwner();

    String getName();

    Type getServiceType();

    Type[] getParameterTypes();

    Object invoke(Object target, Object... args);

    Method getMethod();
}
