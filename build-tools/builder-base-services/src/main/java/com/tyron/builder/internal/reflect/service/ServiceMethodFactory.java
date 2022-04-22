package com.tyron.builder.internal.reflect.service;

import java.lang.reflect.Method;

interface ServiceMethodFactory {
    ServiceMethod toServiceMethod(Method method);
}