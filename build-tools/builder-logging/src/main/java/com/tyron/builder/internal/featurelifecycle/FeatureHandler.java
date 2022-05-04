package com.tyron.builder.internal.featurelifecycle;

/**
 * Notified when a deprecated feature is used.
 *
 * <p>Implementations need not be thread-safe.
 */
public interface FeatureHandler<T extends FeatureUsage> {
    void featureUsed(T usage);
}
