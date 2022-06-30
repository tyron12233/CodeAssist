package com.tyron.builder.api.internal;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class FeaturePreviews {

    /**
     * Feature previews that can be turned on.
     * A feature that is no longer relevant will have the {@code active} flag set to {@code false}.
     */
    public enum Feature {
        GROOVY_COMPILATION_AVOIDANCE(true),
        ONE_LOCKFILE_PER_PROJECT(false),
        VERSION_ORDERING_V2(false),
        VERSION_CATALOGS(true),
        TYPESAFE_PROJECT_ACCESSORS(true);

        public static Feature withName(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                // Re-wording to exception message to get rid of the fqcn it contains
                throw new IllegalArgumentException("There is no feature named " + name);
            }
        }

        private final boolean active;

        Feature(boolean active) {
            this.active = active;
        }

        public boolean isActive() {
            return active;
        }
    }

    private final Set<Feature> activeFeatures;
    private final EnumSet<Feature> enabledFeatures = EnumSet.noneOf(Feature.class);

    public FeaturePreviews() {
        Set<Feature> tmpActiveSet = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (feature.isActive()) {
                tmpActiveSet.add(feature);
            }
        }
        activeFeatures = Collections.unmodifiableSet(tmpActiveSet);
    }

    public void enableFeature(Feature feature) {
        if (feature.isActive()) {
            enabledFeatures.add(feature);
        }
    }

    public boolean isFeatureEnabled(Feature feature) {
        return feature.isActive() && enabledFeatures.contains(feature);
    }

    /**
     * Returns the set of active {@linkplain Feature features}.
     */
    public Set<Feature> getActiveFeatures() {
        return activeFeatures;
    }
}
