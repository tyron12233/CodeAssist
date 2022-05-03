package com.tyron.builder.internal.featurelifecycle;

public interface UsageLocationReporter {
    void reportLocation(FeatureUsage usage, StringBuilder target);
}
