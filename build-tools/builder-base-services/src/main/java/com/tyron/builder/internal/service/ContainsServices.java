package com.tyron.builder.internal.service;

/**
 * Represents a source of services.
 */
interface ContainsServices {
    ServiceProvider asProvider();
}