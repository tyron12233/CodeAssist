package org.gradle.internal.service;

/**
 * Represents a source of services.
 */
interface ContainsServices {
    ServiceProvider asProvider();
}