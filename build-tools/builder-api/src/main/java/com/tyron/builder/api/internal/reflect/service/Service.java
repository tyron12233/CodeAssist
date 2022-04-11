package com.tyron.builder.api.internal.reflect.service;

/**
 * Wraps a single service instance. Implementations must be thread safe.
 */
interface Service {
    String getDisplayName();

    Object get();

    void requiredBy(ServiceProvider serviceProvider);
}