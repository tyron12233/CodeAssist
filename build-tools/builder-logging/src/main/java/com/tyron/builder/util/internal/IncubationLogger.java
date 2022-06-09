package com.tyron.builder.util.internal;

import com.tyron.builder.internal.featurelifecycle.IncubatingFeatureUsage;
import com.tyron.builder.internal.featurelifecycle.LoggingIncubatingFeatureHandler;

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
