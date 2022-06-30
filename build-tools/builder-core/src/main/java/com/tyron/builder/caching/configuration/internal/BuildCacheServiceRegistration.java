package com.tyron.builder.caching.configuration.internal;

import com.tyron.builder.caching.BuildCacheServiceFactory;
import com.tyron.builder.caching.configuration.BuildCache;

public interface BuildCacheServiceRegistration {
    Class<? extends BuildCache> getConfigurationType();
    Class<? extends BuildCacheServiceFactory<?>> getFactoryType();
}
