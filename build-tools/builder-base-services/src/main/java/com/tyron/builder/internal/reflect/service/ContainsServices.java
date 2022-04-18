package com.tyron.builder.internal.reflect.service;

/**
 * Represents a source of services.
 */
interface ContainsServices {
    ServiceProvider asProvider();
}