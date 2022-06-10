package org.gradle.internal.featurelifecycle;

public class IncubatingFeatureUsage extends FeatureUsage {
    public IncubatingFeatureUsage(String summary, Class<?> calledFrom) {
        super(summary, calledFrom);
    }
}
