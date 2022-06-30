package com.tyron.builder.internal.service;

import java.lang.reflect.Method;

interface ServiceMethodFactory {
    ServiceMethod toServiceMethod(Method method);
}