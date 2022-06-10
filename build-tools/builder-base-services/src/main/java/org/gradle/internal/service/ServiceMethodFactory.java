package org.gradle.internal.service;

import java.lang.reflect.Method;

interface ServiceMethodFactory {
    ServiceMethod toServiceMethod(Method method);
}