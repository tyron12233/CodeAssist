package com.tyron.builder.internal.featurelifecycle;

public class IncubatingFeatureUsage extends FeatureUsage {
    public IncubatingFeatureUsage(String summary, Class<?> calledFrom) {
        super(summary, calledFrom);
    }
}
