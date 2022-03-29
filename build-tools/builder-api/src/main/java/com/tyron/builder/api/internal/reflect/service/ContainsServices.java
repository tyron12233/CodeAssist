package com.tyron.builder.api.internal.reflect.service;

/**
 * Represents a source of services.
 */
interface ContainsServices {
    ServiceProvider asProvider();
}