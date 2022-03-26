package com.tyron.builder.api.internal.service;

import java.lang.reflect.Method;

interface ServiceMethodFactory {
    ServiceMethod toServiceMethod(Method method);
}