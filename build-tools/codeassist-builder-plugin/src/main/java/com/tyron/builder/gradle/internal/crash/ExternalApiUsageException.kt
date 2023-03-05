package com.tyron.builder.gradle.internal.crash

/**
 * An exception that can be thrown when running code that we do not control. Typically, these are
 * callbacks supplied through our APIs.
 */
class ExternalApiUsageException(t: Throwable):RuntimeException(t)