package com.tyron.builder.internal.service;

import javax.annotation.Nullable;
import java.util.List;

public interface ServiceLocator {
    <T> T get(Class<T> serviceType) throws UnknownServiceException;

    <T> List<T> getAll(Class<T> serviceType) throws UnknownServiceException;

    <T> DefaultServiceLocator.ServiceFactory<T> getFactory(Class<T> serviceType) throws UnknownServiceException;

    @Nullable
    <T> DefaultServiceLocator.ServiceFactory<T> findFactory(Class<T> serviceType);
}

