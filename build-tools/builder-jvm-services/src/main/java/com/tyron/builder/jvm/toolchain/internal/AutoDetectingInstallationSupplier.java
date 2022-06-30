package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.provider.ProviderFactory;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Set;

public abstract class AutoDetectingInstallationSupplier implements InstallationSupplier {

    public static final String AUTO_DETECT = "org.gradle.java.installations.auto-detect";
    private final ProviderFactory factory;
    private final Provider<Boolean> detectionEnabled;

    @Inject
    public AutoDetectingInstallationSupplier(ProviderFactory factory) {
        this.detectionEnabled = factory.gradleProperty(AUTO_DETECT).map(Boolean::parseBoolean);
        this.factory = factory;
    }

    @Override
    public Set<InstallationLocation> get() {
        if (isAutoDetectionEnabled()) {
            return findCandidates();
        }
        return Collections.emptySet();
    }

    protected Provider<String> getEnvironmentProperty(String propertyName) {
        return factory.environmentVariable(propertyName);
    }

    protected Provider<String> getSystemProperty(String propertyName) {
        return factory.systemProperty(propertyName);
    }

    protected abstract Set<InstallationLocation> findCandidates();

    protected boolean isAutoDetectionEnabled() {
        return detectionEnabled.getOrElse(true);
    }

}
