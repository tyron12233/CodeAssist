package org.gradle.internal.featurelifecycle;

public interface UsageLocationReporter {
    void reportLocation(FeatureUsage usage, StringBuilder target);
}
