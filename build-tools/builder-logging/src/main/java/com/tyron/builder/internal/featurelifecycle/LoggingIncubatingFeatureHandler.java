package com.tyron.builder.internal.featurelifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

public class LoggingIncubatingFeatureHandler implements FeatureHandler<IncubatingFeatureUsage> {
    private static final String INCUBATION_MESSAGE = "%s is an incubating feature.";
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingIncubatingFeatureHandler.class);

    private final Set<String> features = new HashSet<String>();

    @Override
    public void featureUsed(IncubatingFeatureUsage usage) {
        if (features.add(usage.getSummary())) {
            LOGGER.warn(String.format(INCUBATION_MESSAGE, usage.getSummary()));
        }
    }

    public void reset() {
        features.clear();
    }
}
