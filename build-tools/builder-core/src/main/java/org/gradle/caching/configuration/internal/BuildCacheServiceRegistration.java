package org.gradle.caching.configuration.internal;

import org.gradle.caching.BuildCacheServiceFactory;
import org.gradle.caching.configuration.BuildCache;

public interface BuildCacheServiceRegistration {
    Class<? extends BuildCache> getConfigurationType();
    Class<? extends BuildCacheServiceFactory<?>> getFactoryType();
}
