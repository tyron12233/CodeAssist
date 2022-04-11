package com.tyron.builder.api.internal.reflect.service;

import static com.tyron.builder.api.internal.Cast.uncheckedNonnullCast;

import java.lang.reflect.Method;

/**
 * A service method factory that will try to use method handles if available, otherwise fallback on reflection.
 */
class DefaultServiceMethodFactory implements ServiceMethodFactory {
    private final ServiceMethodFactory delegate = getOptimalServiceMethodFactory();

    private ServiceMethodFactory getOptimalServiceMethodFactory() {
        try {
            return uncheckedNonnullCast(
                    Class.forName("org.gradle.internal.service.MethodHandleBasedServiceMethodFactory").getConstructor().newInstance()
            );
        } catch (Exception e) {
            return new ReflectionBasedServiceMethodFactory();
        }
    }

    @Override
    public ServiceMethod toServiceMethod(Method method) {
        return delegate.toServiceMethod(method);
    }
}