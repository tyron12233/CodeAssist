package com.tyron.builder.api.internal.reflect.service;

import org.jetbrains.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * A read-only supplier of services.
 */
public interface ServiceLookup {
    @Nullable
    Object find(Type serviceType) throws ServiceLookupException;

    Object get(Type serviceType) throws UnknownServiceException, ServiceLookupException;

    Object get(Type serviceType, Class<? extends Annotation> annotatedWith) throws UnknownServiceException, ServiceLookupException;
}