package com.tyron.builder.api.internal.service;

/**
 * Represents a source of services.
 */
interface ContainsServices {
    ServiceProvider asProvider();
}