package org.gradle.util.internal;

import org.gradle.internal.featurelifecycle.IncubatingFeatureUsage;
import org.gradle.internal.featurelifecycle.LoggingIncubatingFeatureHandler;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class IncubationLogger {
    public static final String INCUBATION_MESSAGE = "%s is an incubating feature.";

    private static final LoggingIncubatingFeatureHandler INCUBATING_FEATURE_HANDLER = new LoggingIncubatingFeatureHandler();

    public synchronized static void reset() {
        INCUBATING_FEATURE_HANDLER.reset();
    }

    public static synchronized void incubatingFeatureUsed(String incubatingFeature) {
        INCUBATING_FEATURE_HANDLER.featureUsed(new IncubatingFeatureUsage(incubatingFeature, IncubationLogger.class));
    }

}
